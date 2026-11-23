package com.p2pworld.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.shared.GoogleAuthService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cryptographic Anti-Spoofing Guard for Offline LAN & Shared Worlds.
 * Verifies each joining player's Google-signed OIDC id_token via Google's tokeninfo API
 * and stores a non-reversible SHA-256 identity hash in <world>/fabricp2p_identities.json
 * (never leaking plaintext email addresses in shared world files).
 */
public final class PlayerIdentityGuard {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String IDENTITY_FILE = "fabricp2p_identities.json";
    private static final String HASH_PREFIX = "sha256:";
    private static final String SUB_HASH_PREFIX = "sub-sha256:";
    private static final int MAX_VERIFICATION_ATTEMPTS_PER_SESSION = 2;
    private static final long VERIFICATION_TIMEOUT_SECONDS = 15L;
    private static final double ANCHOR_DRIFT_LIMIT_SQR = 0.5 * 0.5;

    private record PendingAnchor(
            ResourceKey<Level> dimension,
            double x,
            double y,
            double z
    ) {}

    private static final AtomicLong JOIN_SEQ = new AtomicLong(0);
    private static final AtomicLong VERIFY_SEQ = new AtomicLong(0);
    private static final Map<UUID, Long> activeJoinToken = new ConcurrentHashMap<>();
    private static final Set<UUID> verifiedThisSession = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> verifiedSessionEmails = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> verificationInFlight = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicInteger> verificationAttempts = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingAnchor> pendingVerification = new ConcurrentHashMap<>();

    private PlayerIdentityGuard() {}

    private static boolean isLocalHostPlayer(ServerPlayer player, MinecraftServer server) {
        if (!server.isSingleplayerOwner(player.nameAndId())) {
            return false;
        }
        if (player.connection == null) {
            return false;
        }
        SocketAddress remoteAddr = player.connection.getRemoteAddress();
        return remoteAddr != null && !(remoteAddr instanceof InetSocketAddress);
    }

    /**
     * Returns true if the player has a bound username and has not yet completed Google OIDC verification.
     * While true, movement and world interactions are restricted by the server.
     */
    public static boolean isPendingVerification(UUID uuid) {
        return uuid != null && pendingVerification.containsKey(uuid);
    }

    public static String getVerifiedEmail(UUID uuid) {
        return uuid != null ? verifiedSessionEmails.get(uuid) : null;
    }

    /**
     * Prevents duplicate/spam GoogleIdentityC2SPayload verification requests from an already-verified
     * or currently verifying player. Returns a positive attempt token, or 0L if rejected.
     */
    public static long tryBeginVerificationToken(UUID uuid) {
        if (uuid == null || verifiedThisSession.contains(uuid)) {
            return 0L;
        }
        AtomicInteger attempts = verificationAttempts.computeIfAbsent(uuid, k -> new AtomicInteger(0));
        if (attempts.get() >= MAX_VERIFICATION_ATTEMPTS_PER_SESSION) {
            return 0L;
        }
        long token = VERIFY_SEQ.incrementAndGet();
        if (verificationInFlight.putIfAbsent(uuid, token) != null) {
            return 0L;
        }
        if (attempts.incrementAndGet() > MAX_VERIFICATION_ATTEMPTS_PER_SESSION) {
            verificationInFlight.remove(uuid, token);
            return 0L;
        }
        return token;
    }

    public static boolean tryBeginVerification(UUID uuid) {
        return tryBeginVerificationToken(uuid) > 0L;
    }

    public static void endVerificationAttempt(UUID uuid, long attemptToken) {
        if (uuid != null && attemptToken > 0L) {
            verificationInFlight.remove(uuid, attemptToken);
        }
    }

    public static void endVerificationAttempt(UUID uuid) {
        if (uuid != null) {
            verificationInFlight.remove(uuid);
        }
    }

    /**
     * Keeps any pending-verification players anchored at their join position so unverified spoofers
     * cannot roam, push entities, or interact with the world during the verification grace window.
     */
    public static void enforcePendingAnchors(MinecraftServer server) {
        if (server == null || pendingVerification.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PendingAnchor> entry : pendingVerification.entrySet()) {
            UUID uuid = entry.getKey();
            PendingAnchor anchor = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                pendingVerification.remove(uuid);
                continue;
            }
            if (player.connection == null) {
                continue;
            }
            if (!player.level().dimension().equals(anchor.dimension())) {
                ServerLevel targetLevel = server.getLevel(anchor.dimension());
                if (targetLevel != null) {
                    player.teleportTo(
                            targetLevel,
                            anchor.x(),
                            anchor.y(),
                            anchor.z(),
                            Set.of(),
                            player.getYRot(),
                            player.getXRot(),
                            false
                    );
                    player.setDeltaMovement(0.0, 0.0, 0.0);
                    player.resetFallDistance();
                }
                continue;
            }
            if (player.distanceToSqr(anchor.x(), anchor.y(), anchor.z()) > ANCHOR_DRIFT_LIMIT_SQR) {
                player.setDeltaMovement(0.0, 0.0, 0.0);
                player.resetFallDistance();
                player.connection.teleport(
                        anchor.x(),
                        anchor.y(),
                        anchor.z(),
                        player.getYRot(),
                        player.getXRot()
                );
            }
        }
    }

    /**
     * Called when a player joins the server. If their username is already bound to a Google account,
     * they are held in a restricted pending-verification state and must present a valid Google-signed
     * id_token matching that account within 15 seconds or be disconnected.
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null || player == null) return;
        UUID uuid = player.getUUID();
        verifiedThisSession.remove(uuid);
        verifiedSessionEmails.remove(uuid);
        verificationInFlight.remove(uuid);
        verificationAttempts.remove(uuid);
        pendingVerification.remove(uuid);
        long joinToken = JOIN_SEQ.incrementAndGet();
        activeJoinToken.put(uuid, joinToken);

        // Never lock the local in-memory singleplayer host out of their own world (e.g. when playing offline),
        // but still allow their GoogleIdentityC2SPayload to flow through verifyAndBind() once so their username gets bound.
        if (isLocalHostPlayer(player, server)) {
            return;
        }

        String usernameKey = player.getName().getString().toLowerCase(Locale.ROOT);
        Map<String, String> bindings = loadBindings(server);
        if (bindings != null) {
            String boundIdentity = bindings.get(usernameKey);
            if (boundIdentity == null || boundIdentity.isBlank()) {
                return; // Not yet bound to any Google account
            }
        }

        // Freeze interactions and anchor join position until OIDC token verification completes
        pendingVerification.put(uuid, new PendingAnchor(
                player.level().dimension(),
                player.getX(),
                player.getY(),
                player.getZ()
        ));

        // Wait up to 15 seconds for client to refresh/send & server to verify Google OIDC id_token
        CompletableFuture.delayedExecutor(VERIFICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (!server.isRunning()) return;
            server.execute(() -> {
                Long currentToken = activeJoinToken.get(uuid);
                // Ignore stale timer if player already disconnected and reconnected
                if (currentToken == null || currentToken != joinToken) {
                    return;
                }
                if (!verifiedThisSession.contains(uuid)) {
                    pendingVerification.remove(uuid);
                    ServerPlayer target = server.getPlayerList().getPlayer(uuid);
                    if (target != null) {
                        FabricP2PMod.LOGGER.warn("[IdentityGuard] Kicked unverified player '{}' (bound to Google account).",
                                target.getName().getString());
                        target.connection.disconnect(Component.literal(
                                "§c[FabricP2P] Username '" + target.getName().getString()
                                        + "' is protected by Google Sign-In!\n§7Sign in with the owner's Google account in Shared Worlds."
                        ));
                    }
                }
            });
        });
    }

    /**
     * Verifies a Google-verified identity (obtained from Google's tokeninfo endpoint) against the world's bindings.
     * Returns true if allowed (newly bound or matches existing binding), false if spoofed & kicked.
     */
    public static boolean verifyAndBind(ServerPlayer player, GoogleAuthService.VerifiedGoogleIdentity verified, MinecraftServer server) {
        if (player == null || server == null) return false;
        UUID uuid = player.getUUID();
        String username = player.getName().getString();
        String usernameKey = username.toLowerCase(Locale.ROOT);

        Map<String, String> bindings = loadBindings(server);
        if (bindings == null) {
            pendingVerification.remove(uuid);
            if (isLocalHostPlayer(player, server)) {
                verifiedThisSession.add(uuid);
                return true;
            }
            FabricP2PMod.LOGGER.warn("[IdentityGuard] Identity bindings file unreadable; failing closed for '{}'.", username);
            player.connection.disconnect(Component.literal(
                    "§c[FabricP2P] Identity verification unavailable!\n§7Could not read world identity bindings."
            ));
            return false;
        }

        String existingBinding = bindings.get(usernameKey);
        String subHash = (verified != null && verified.subjectId() != null && !verified.subjectId().isBlank())
                ? computeSubjectHash(verified.subjectId())
                : "";
        String emailHash = (verified != null && verified.email() != null && verified.email().contains("@"))
                ? computeIdentityHash(verified.email())
                : "";

        if (existingBinding != null && !existingBinding.isBlank()) {
            boolean matches = false;
            if (verified != null) {
                if (existingBinding.startsWith(SUB_HASH_PREFIX)) {
                    matches = !subHash.isEmpty() && existingBinding.equals(subHash);
                } else if (existingBinding.startsWith(HASH_PREFIX)) {
                    matches = !emailHash.isEmpty() && existingBinding.equals(emailHash);
                    if (matches && !subHash.isEmpty()) {
                        bindings.put(usernameKey, subHash);
                        saveBindings(server, bindings);
                    }
                } else {
                    // Legacy v2.1.0 plaintext email binding: compare & transparently migrate to immutable sub hash
                    matches = existingBinding.equalsIgnoreCase(verified.email());
                    if (matches) {
                        bindings.put(usernameKey, !subHash.isEmpty() ? subHash : emailHash);
                        saveBindings(server, bindings);
                    }
                }
            }

            if (!matches) {
                if (isLocalHostPlayer(player, server)) {
                    pendingVerification.remove(uuid);
                    verifiedThisSession.add(uuid);
                    return true;
                }
                pendingVerification.remove(uuid);
                if (verified == null) {
                    FabricP2PMod.LOGGER.warn("[IdentityGuard] Rejected unverified player '{}' (bound to Google account).", username);
                    player.connection.disconnect(Component.literal(
                            "§c[FabricP2P] Username '" + username
                                    + "' is protected by Google Sign-In!\n§7Sign in with the owner's Google account in Shared Worlds."
                    ));
                } else {
                    FabricP2PMod.LOGGER.warn("[IdentityGuard] Blocked nickname spoof for '{}'!", username);
                    player.connection.disconnect(Component.literal(
                            "§c[FabricP2P] Access Denied!\n§7Username '" + username
                                    + "' belongs to a different Google account."
                    ));
                }
                return false;
            }
            pendingVerification.remove(uuid);
            verifiedThisSession.add(uuid);
            if (verified != null && verified.email() != null && !verified.email().isBlank()) {
                verifiedSessionEmails.put(uuid, verified.email());
            }
            return true;
        }

        // First time this username joins with a cryptographically verified Google account -> bind immutable sub hash!
        String newBinding = !subHash.isEmpty() ? subHash : emailHash;
        if (!newBinding.isEmpty()) {
            bindings.put(usernameKey, newBinding);
            saveBindings(server, bindings);
            pendingVerification.remove(uuid);
            verifiedThisSession.add(uuid);
            if (verified != null && verified.email() != null && !verified.email().isBlank()) {
                verifiedSessionEmails.put(uuid, verified.email());
            }
            FabricP2PMod.LOGGER.info("[IdentityGuard] Bound username '{}' to verified Google account.", username);
            player.sendSystemMessage(Component.literal(
                    "§a🔒 [FabricP2P] §fUsername §b" + username + " §fis now protected by your verified Google account!"
            ));
            return true;
        }

        if (isLocalHostPlayer(player, server)) {
            verifiedThisSession.add(uuid);
        }
        pendingVerification.remove(uuid);
        return true;
    }

    public static void onPlayerDisconnect(UUID uuid) {
        if (uuid == null) return;
        verifiedThisSession.remove(uuid);
        verifiedSessionEmails.remove(uuid);
        verificationInFlight.remove(uuid);
        verificationAttempts.remove(uuid);
        activeJoinToken.remove(uuid);
        pendingVerification.remove(uuid);
    }

    public static void onServerStopped() {
        verifiedThisSession.clear();
        verifiedSessionEmails.clear();
        verificationInFlight.clear();
        verificationAttempts.clear();
        activeJoinToken.clear();
        pendingVerification.clear();
    }

    private static String computeSubjectHash(String subjectId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("fabricp2p-sub-v3:" + subjectId.trim())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SUB_HASH_PREFIX);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String computeIdentityHash(String normalizedEmail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("fabricp2p-id-v2:" + normalizedEmail.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_PREFIX);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static synchronized Map<String, String> loadBindings(MinecraftServer server) {
        Map<String, String> map = new ConcurrentHashMap<>();
        try {
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(IDENTITY_FILE);
            if (!Files.exists(file)) {
                return map;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return null;
            }
            Map<String, String> parsed = GSON.fromJson(raw, MAP_TYPE);
            if (parsed == null) {
                return null;
            }
            map.putAll(parsed);
            return map;
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[IdentityGuard] Failed to read identity bindings: {}", e.getMessage());
            return null;
        }
    }

    private static synchronized void saveBindings(MinecraftServer server, Map<String, String> bindings) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(IDENTITY_FILE);
        Path tmp = null;
        boolean moved = false;
        try {
            tmp = Files.createTempFile(file.getParent(), "." + IDENTITY_FILE + ".", ".p2p.tmp");
            Files.writeString(tmp, GSON.toJson(bindings), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFallback) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[IdentityGuard] Failed to save identity bindings: {}", e.getMessage());
        } finally {
            if (!moved && tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }
}
