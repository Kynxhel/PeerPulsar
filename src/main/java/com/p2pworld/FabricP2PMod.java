package com.p2pworld;

import com.p2pworld.client.shared.GoogleAuthService;
import com.p2pworld.client.shared.GoogleDriveService;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.client.shared.SharedWorldManager;
import com.p2pworld.network.P2PIdentityPayloads;
import com.p2pworld.server.PlayerIdentityGuard;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class FabricP2PMod implements ModInitializer {
    public static final String MOD_ID = "fabricp2p";
    public static final String MOD_NAME = "Fabric P2P World";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[FabricP2P] Initializing {} (MC 26.3)", MOD_NAME);

        // Register custom networking payloads for Google OIDC Identity Lock & Scoped Drive Sharing
        PayloadTypeRegistry.serverboundPlay().register(
                P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE,
                P2PIdentityPayloads.GoogleIdentityC2SPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                P2PIdentityPayloads.SharedWorldGrantS2CPayload.TYPE,
                P2PIdentityPayloads.SharedWorldGrantS2CPayload.CODEC
        );

        // Enforce Google username binding on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PlayerIdentityGuard.onPlayerJoin(handler.player, server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlayerIdentityGuard.onPlayerDisconnect(handler.player.getUUID());
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PlayerIdentityGuard.onServerStopped();
        });

        // Restrict world interactions and movement while a bound player is pending OIDC verification
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && PlayerIdentityGuard.isPendingVerification(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && PlayerIdentityGuard.isPendingVerification(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && PlayerIdentityGuard.isPendingVerification(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && PlayerIdentityGuard.isPendingVerification(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() && PlayerIdentityGuard.isPendingVerification(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(PlayerIdentityGuard::enforcePendingAnchors);

        // Handle incoming Google OIDC ID Token handshake from joining players
        ServerPlayNetworking.registerGlobalReceiver(
                P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer joiningPlayer = context.player();
                    UUID playerUuid = joiningPlayer.getUUID();
                    MinecraftServer server = context.server();
                    String idToken = payload.idToken();

                    if (!PlayerIdentityGuard.tryBeginVerification(playerUuid)) {
                        return;
                    }

                    // Verify OIDC id_token asynchronously with Google's tokeninfo endpoint (never trust raw client claims)
                    CompletableFuture.supplyAsync(() -> GoogleAuthService.verifyIdTokenWithGoogle(idToken))
                            .whenComplete((verified, err) -> {
                                try {
                                    if (err != null) {
                                        PlayerIdentityGuard.endVerificationAttempt(playerUuid);
                                        LOGGER.warn("[IdentityGuard] Unexpected error verifying OIDC token: {}", err.getMessage());
                                        return;
                                    }
                                    server.execute(() -> {
                                        try {
                                            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
                                            if (player == null || player != joiningPlayer) return;

                                            boolean allowed = PlayerIdentityGuard.verifyAndBind(player, verified, server);
                                            if (!allowed || verified == null) {
                                                return;
                                            }

                                            // Auto-share ONLY the current world's Drive subfolder (never the entire root folder),
                                            // and ONLY when the hosted world is actually a registered Shared World!
                                            SharedWorldConfig cfg = SharedWorldConfig.getInstance();
                                            SharedWorldManager sharedMgr = SharedWorldManager.getInstance();
                                            if (cfg.isEnabled() && cfg.isGoogleSignedIn() && sharedMgr.isCurrentWorldShared(server)) {
                                                // Don't re-share with ourselves
                                                if (verified.email().equalsIgnoreCase(cfg.getGoogleUserEmail())) {
                                                    return;
                                                }
                                                String rawWorldName = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
                                                String worldName = SharedWorldManager.sanitizeName(rawWorldName);
                                                if (worldName.isEmpty()) return;

                                                CompletableFuture.runAsync(() -> {
                                                    try {
                                                        String worldFolderId = sharedMgr.resolveWorldDriveFolderId(worldName, false);
                                                        if (worldFolderId == null || worldFolderId.isBlank()) {
                                                            return;
                                                        }
                                                        boolean shared = GoogleDriveService.getInstance()
                                                                .shareFolderWithEmail(worldFolderId, verified.email());
                                                        if (shared && server.isRunning()) {
                                                            server.execute(() -> {
                                                                ServerPlayer target = server.getPlayerList().getPlayer(playerUuid);
                                                                if (target != null && target == joiningPlayer) {
                                                                    ServerPlayNetworking.send(target,
                                                                            new P2PIdentityPayloads.SharedWorldGrantS2CPayload(worldFolderId, worldName));
                                                                }
                                                            });
                                                        }
                                                    } catch (Exception e) {
                                                        LOGGER.warn("[SharedWorlds] Auto-share with {} failed: {}", verified.email(), e.getMessage());
                                                    }
                                                });
                                            }
                                        } finally {
                                            PlayerIdentityGuard.endVerificationAttempt(playerUuid);
                                        }
                                    });
                                } catch (Throwable t) {
                                    PlayerIdentityGuard.endVerificationAttempt(playerUuid);
                                }
                            });
                }
        );
    }
}
