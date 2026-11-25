package com.kynxhel.peerpulsar.security;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.mixin.ConnectionAccessor;
import com.kynxhel.peerpulsar.mixin.ServerLoginPacketListenerAccessor;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Server-side login challenge and authentication coordinator for PeerPulsar.
 * <p>
 * Layer 1 (Room Key): Enforced in both Online and Offline modes on all internet hosting modes
 * (Direct UPnP, UDP Hole Punch, Tunnel Relay), and optionally on Local LAN.
 * <br>
 * Layer 2 (Join Approval): Optional per-connection host approval with device fingerprint matching.
 * <br>
 * Player Identity (when Offline LAN Auth is enabled): Channel-bound encrypted login handshake,
 * deterministic Ed25519-key UUIDs, and safe one-time legacy data migration.
 */
public final class OfflineAuth {
    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("peerpulsar", "offline_auth");
    public static final int PROTOCOL = 3;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Executor FALLBACK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PeerPulsar-AuthWorker");
        t.setDaemon(true);
        return t;
    });

    private static final Map<ServerLoginPacketListenerImpl, PendingQuery> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ServerLoginPacketListenerImpl, String> PENDING_APPROVAL_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile HostSecurityContext hostContext;

    public interface HostSecurityContext {
        boolean isHostingActiveOrStarting();
        boolean isOfflineAuthEnabled();
        boolean isRoomKeyRequired();
        boolean isJoinApprovalEnabled();
        String getHostRoomKey();
        String resolveRemoteIp(Connection connection);
        Executor getAsyncExecutor();
        default void onPreApprovedJoin(String playerName) {}
    }

    private record PendingQuery(
            byte[] nonce,
            byte[] hostPubKeyHash,
            String remoteIp,
            boolean offlineMode,
            boolean roomKeyRequired,
            boolean joinApproval
    ) {}

    private OfflineAuth() {}

    public static void setHostContext(HostSecurityContext context) {
        hostContext = context;
    }

    public static boolean shouldChallenge(
            boolean hostingActiveOrStarting,
            boolean isMemoryConnection,
            boolean roomKeyRequired,
            boolean offlineAuth,
            boolean joinApproval
    ) {
        return !isMemoryConnection
                && hostingActiveOrStarting
                && (roomKeyRequired || offlineAuth || joinApproval);
    }

    public static boolean isRoomKeyRequiredForHosting(boolean isLocalLan, boolean requireOnLanSetting) {
        return !isLocalLan || requireOnLanSetting;
    }

    private static boolean isConnectionEncrypted(Connection connection) {
        if (connection == null) {
            return false;
        }
        Channel ch = ((ConnectionAccessor) connection).peerpulsar$getChannel();
        return ch != null && ch.pipeline().get("decrypt") != null && ch.pipeline().get("encrypt") != null;
    }

    public static void registerServerSide() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            HostSecurityContext ctx = hostContext;
            if (ctx == null) return;

            Connection connection = ((ServerLoginPacketListenerAccessor) handler).peerpulsar$getConnection();
            boolean isMemory = connection == null || connection.isMemoryConnection();
            boolean offlineMode = ctx.isOfflineAuthEnabled();
            boolean roomKeyRequired = ctx.isRoomKeyRequired();
            boolean joinApproval = ctx.isJoinApprovalEnabled();

            if (!shouldChallenge(ctx.isHostingActiveOrStarting(), isMemory, roomKeyRequired, offlineMode, joinApproval)) {
                return;
            }

            if (!isConnectionEncrypted(connection)) {
                handler.disconnect(Component.literal("Encrypted PeerPulsar handshake required."));
                return;
            }

            String remoteIp = ctx.resolveRemoteIp(connection);
            if (roomKeyRequired && BruteForceGuard.getGlobal().isBlocked(remoteIp)) {
                handler.disconnect(Component.literal("Too many failed attempts. Try again later."));
                return;
            }

            byte[] nonce = new byte[32];
            RNG.nextBytes(nonce);
            byte[] hostPubKeyHash = RoomKeys.sha256(server.getKeyPair().getPublic().getEncoded());

            PENDING.put(handler, new PendingQuery(nonce, hostPubKeyHash, remoteIp, offlineMode, roomKeyRequired, joinApproval));

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeVarInt(PROTOCOL);
            buf.writeByteArray(nonce);
            buf.writeBoolean(offlineMode);
            sender.sendPacket(CHANNEL, buf);
        });

        ServerLoginNetworking.registerGlobalReceiver(CHANNEL, (server, handler, understood, buf, synchronizer, responseSender) -> {
            PendingQuery query = PENDING.remove(handler);
            if (query == null) {
                return;
            }

            HostSecurityContext ctx = hostContext;
            if (!understood || buf == null) {
                if (query.roomKeyRequired()) {
                    BruteForceGuard.getGlobal().recordFailure(query.remoteIp());
                }
                handler.disconnect(Component.literal(
                        "This world requires PeerPulsar.\nInstall PeerPulsar and join from 'Join P2P' with the full room code (#KEY)."
                ));
                return;
            }

            final int version;
            final boolean hasKey;
            final byte[] proof;
            final byte[] publicKey;
            final byte[] signature;
            try {
                version = buf.readVarInt();
                if (version != PROTOCOL) {
                    handler.disconnect(Component.literal("PeerPulsar version mismatch. Please update PeerPulsar on both computers."));
                    return;
                }
                hasKey = buf.readBoolean();
                proof = buf.readByteArray(64);
                publicKey = buf.readByteArray(256);
                signature = buf.readByteArray(256);
            } catch (Exception e) {
                if (query.roomKeyRequired()) {
                    BruteForceGuard.getGlobal().recordFailure(query.remoteIp());
                }
                handler.disconnect(Component.literal("PeerPulsar login failed: malformed response."));
                return;
            }

            String name = profileName(handler);
            if (name == null || name.isBlank()) {
                handler.disconnect(Component.literal("Login failed: missing player name."));
                return;
            }

            // Layer 1: Room Key verification (silent to host on failure)
            if (query.roomKeyRequired()) {
                String roomKey = ctx != null ? ctx.getHostRoomKey() : null;
                if (roomKey == null) {
                    handler.disconnect(Component.literal("The host is not accepting players right now."));
                    return;
                }
                if (!hasKey || !MessageDigest.isEqual(RoomKeys.loginProof(roomKey, query.nonce(), name, query.hostPubKeyHash()), proof)) {
                    BruteForceGuard.getGlobal().recordFailure(query.remoteIp());
                    handler.disconnect(Component.literal("Wrong or missing room key. Ask the host for the current room code."));
                    return;
                }
            }

            // Host username reservation (prevents impersonating host even via handleHello spProfile shortcut)
            GameProfile hostProfile = server.getSingleplayerProfile();
            if (hostProfile != null && hostProfile.name() != null && hostProfile.name().equalsIgnoreCase(name)) {
                handler.disconnect(Component.literal("The name '" + name + "' belongs to the host of this world."));
                return;
            }

            // Ed25519 device identity signature verification (channel-bound to host public key hash)
            if (!ClientIdentity.verify(publicKey, RoomKeys.identityPayload(query.nonce(), name, query.hostPubKeyHash()), signature)) {
                handler.disconnect(Component.literal("PeerPulsar identity check failed."));
                return;
            }

            Executor exec = (ctx != null && ctx.getAsyncExecutor() != null) ? ctx.getAsyncExecutor() : FALLBACK_EXECUTOR;
            CompletableFuture<Void> gateFuture = CompletableFuture.runAsync(() -> {
                try {
                    completeLoginVerification(server, handler, query, name, publicKey);
                } catch (Exception e) {
                    PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Verification error for '{}': {}", name, e.getMessage());
                    server.execute(() -> handler.disconnect(Component.literal("PeerPulsar authentication failed.")));
                } finally {
                    PENDING_APPROVAL_IDS.remove(handler);
                }
            }, exec);
            synchronizer.waitFor(gateFuture);
        });

        ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> {
            PENDING.remove(handler);
            String approvalId = PENDING_APPROVAL_IDS.remove(handler);
            if (approvalId != null) {
                JoinApprovalManager.getInstance().cancel(approvalId);
            }
        });
    }

    private static void completeLoginVerification(
            MinecraftServer server,
            ServerLoginPacketListenerImpl handler,
            PendingQuery query,
            String name,
            byte[] publicKey
    ) throws Exception {
        String keyFingerprint = RoomKeys.hex(RoomKeys.sha256(publicKey));
        Path worldDir = server.getWorldPath(LevelResource.ROOT);

        // 1. Resolve effective profile: deterministic Ed25519-key UUID when Offline LAN Auth is active
        GameProfile resolvedOfflineProfile = null;
        String mojangUuid = null;
        if (query.offlineMode()) {
            resolvedOfflineProfile = new GameProfile(PlayerDataMigrator.deterministicKeyUuid(publicKey), name);
        } else {
            GameProfile existingAuth = ((ServerLoginPacketListenerAccessor) handler).peerpulsar$getAuthenticatedProfile();
            if (existingAuth != null && existingAuth.id() != null) {
                mojangUuid = existingAuth.id().toString();
            }
        }

        UUID effectiveUuid = resolvedOfflineProfile != null
                ? resolvedOfflineProfile.id()
                : (mojangUuid != null ? UUID.fromString(mojangUuid) : null);
        String effectiveUuidStr = effectiveUuid != null ? effectiveUuid.toString() : null;

        String existingLock = query.offlineMode() ? NameLockStore.getBoundFingerprint(worldDir, name) : null;
        boolean hasUnclaimedLegacy = query.offlineMode()
                && effectiveUuid != null
                && PlayerDataMigrator.hasUnclaimedLegacyData(worldDir, effectiveUuid, name);

        boolean authorizedToClaimLegacy = false;

        // 2. Layer 2 (Join Approval) & Name Lock enforcement
        if (query.joinApproval()) {
            ApprovedPlayerStore.CheckResult check = ApprovedPlayerStore.check(worldDir, name, keyFingerprint, mojangUuid);
            if (check == ApprovedPlayerStore.CheckResult.APPROVED) {
                ApprovedPlayerStore.touchLastSeen(worldDir, name, keyFingerprint, effectiveUuidStr, mojangUuid);
                HostSecurityContext ctx = hostContext;
                if (ctx != null) {
                    ctx.onPreApprovedJoin(name);
                }
                if (query.offlineMode()) {
                    if (existingLock == null) {
                        NameLockStore.rebind(worldDir, name, keyFingerprint);
                        authorizedToClaimLegacy = true;
                    } else {
                        authorizedToClaimLegacy = existingLock.equalsIgnoreCase(keyFingerprint);
                    }
                }
            } else {
                boolean diffDevice = (check == ApprovedPlayerStore.CheckResult.DIFFERENT_DEVICE)
                        || (query.offlineMode() && existingLock != null && !existingLock.equalsIgnoreCase(keyFingerprint));
                JoinApprovalManager.PendingRequest req = JoinApprovalManager.getInstance()
                        .requestApproval(name, keyFingerprint, mojangUuid, diffDevice, hasUnclaimedLegacy);
                PENDING_APPROVAL_IDS.put(handler, req.id());
                JoinApprovalManager.Decision decision = req.future().get();
                if (decision == JoinApprovalManager.Decision.DENY || decision == JoinApprovalManager.Decision.TIMEOUT) {
                    server.execute(() -> handler.disconnect(Component.literal("Host did not approve your join request.")));
                    return;
                }
                if (decision == JoinApprovalManager.Decision.ALWAYS) {
                    ApprovedPlayerStore.approve(worldDir, new ApprovedPlayerStore.ApprovedEntry(
                            name, keyFingerprint, mojangUuid, effectiveUuidStr,
                            System.currentTimeMillis(), System.currentTimeMillis()
                    ));
                    if (query.offlineMode()) {
                        NameLockStore.rebind(worldDir, name, keyFingerprint);
                    }
                } else if (query.offlineMode() && existingLock == null) {
                    NameLockStore.rebind(worldDir, name, keyFingerprint);
                }
                authorizedToClaimLegacy = query.offlineMode()
                        && (existingLock == null || existingLock.equalsIgnoreCase(keyFingerprint));
            }
        } else if (query.offlineMode()) {
            if (existingLock != null && !existingLock.equalsIgnoreCase(keyFingerprint)) {
                PeerPulsar.LOGGER.info("[PeerPulsar-Auth] Rejected '{}': name locked to another device.", name);
                server.execute(() -> handler.disconnect(Component.literal(
                        "The name '" + name + "' is already claimed by another player in this world.\n"
                                + "Use a different name, or ask the host to remove it from " + NameLockStore.FILE_NAME + "."
                )));
                return;
            }
            if (existingLock == null && hasUnclaimedLegacy) {
                JoinApprovalManager.PendingRequest req = JoinApprovalManager.getInstance()
                        .requestApproval(name, keyFingerprint, mojangUuid, false, true);
                PENDING_APPROVAL_IDS.put(handler, req.id());
                JoinApprovalManager.Decision decision = req.future().get();
                if (decision == JoinApprovalManager.Decision.ACCEPT || decision == JoinApprovalManager.Decision.ALWAYS) {
                    authorizedToClaimLegacy = true;
                    if (decision == JoinApprovalManager.Decision.ALWAYS) {
                        ApprovedPlayerStore.approve(worldDir, new ApprovedPlayerStore.ApprovedEntry(
                                name, keyFingerprint, mojangUuid, effectiveUuidStr,
                                System.currentTimeMillis(), System.currentTimeMillis()
                        ));
                    }
                }
            } else {
                authorizedToClaimLegacy = (existingLock == null || existingLock.equalsIgnoreCase(keyFingerprint));
            }
            NameLockStore.checkAndBind(worldDir, name, publicKey);
        }

        // 3. Migrate legacy offline-name playerdata safely (copy + backup) and apply GameProfile
        if (query.offlineMode() && resolvedOfflineProfile != null) {
            PlayerDataMigrator.migrateLegacyDataIfNeeded(
                    worldDir,
                    resolvedOfflineProfile.id(),
                    name,
                    keyFingerprint,
                    mojangUuid,
                    authorizedToClaimLegacy
            );
            IdentityMapStore.upsert(worldDir, new IdentityMapStore.IdentityRecord(
                    keyFingerprint,
                    mojangUuid,
                    resolvedOfflineProfile.id().toString(),
                    name,
                    null,
                    System.currentTimeMillis()
            ));

            final GameProfile profileToApply = resolvedOfflineProfile;
            CompletableFuture<Void> applyFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ((ServerLoginPacketListenerAccessor) handler).peerpulsar$setAuthenticatedProfile(profileToApply);
                    applyFuture.complete(null);
                } catch (Throwable t) {
                    applyFuture.completeExceptionally(t);
                }
            });
            applyFuture.get(5, TimeUnit.SECONDS);
        }
    }

    private static String profileName(ServerLoginPacketListenerImpl handler) {
        ServerLoginPacketListenerAccessor accessor = (ServerLoginPacketListenerAccessor) handler;
        GameProfile profile = accessor.peerpulsar$getAuthenticatedProfile();
        if (profile != null && profile.name() != null) {
            return profile.name();
        }
        return accessor.peerpulsar$getRequestedUsername();
    }
}
