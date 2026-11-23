package com.p2pworld;

import com.p2pworld.client.shared.GoogleDriveService;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.network.P2PIdentityPayloads;
import com.p2pworld.server.PlayerIdentityGuard;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class FabricP2PMod implements ModInitializer {
    public static final String MOD_ID = "fabricp2p";
    public static final String MOD_NAME = "Fabric P2P World";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[FabricP2P] Initializing {} (MC 26.3)", MOD_NAME);

        // Register custom networking payloads for Google Identity Lock & Auto Drive Sharing
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

        // Handle incoming Google Identity handshake from joining players
        ServerPlayNetworking.registerGlobalReceiver(
                P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    String email = payload.email();

                    server.execute(() -> {
                        boolean allowed = PlayerIdentityGuard.verifyAndBind(player, email, server);
                        if (!allowed || email == null || email.isBlank() || !email.contains("@")) {
                            return;
                        }

                        // If Host has Shared Cloud Worlds + Google Drive signed in, auto-share the Drive folder!
                        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
                        if (cfg.isEnabled() && cfg.isGoogleSignedIn()) {
                            String worldName = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
                            CompletableFuture.runAsync(() -> {
                                try {
                                    GoogleDriveService drive = GoogleDriveService.getInstance();
                                    String rootId = drive.getOrCreateRootFolderId();
                                    boolean shared = drive.shareFolderWithEmail(rootId, email);
                                    if (shared) {
                                        server.execute(() -> {
                                            if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
                                                ServerPlayNetworking.send(player,
                                                        new P2PIdentityPayloads.SharedWorldGrantS2CPayload(rootId, worldName));
                                            }
                                        });
                                    }
                                } catch (Exception e) {
                                    LOGGER.warn("[SharedWorlds] Auto-share with {} failed: {}", email, e.getMessage());
                                }
                            });
                        }
                    });
                }
        );
    }
}
