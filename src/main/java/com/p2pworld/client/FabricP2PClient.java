package com.p2pworld.client;

import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import com.p2pworld.client.shared.GoogleAuthService;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.client.shared.SharedWorldManager;
import com.p2pworld.network.P2PIdentityPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public class FabricP2PClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricP2PMod.LOGGER.info("[FabricP2P-Client] Neon P2P Engine initialized (MC 26.3).");
        P2PNetworkManager.getInstance();

        // Send our Google-signed OIDC id_token to the Host on join (refreshed async so render thread never blocks)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!ClientPlayNetworking.canSend(P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE)) {
                return;
            }
            SharedWorldConfig cfg = SharedWorldConfig.getInstance();
            if (!cfg.isEnabled() || !cfg.isGoogleSignedIn()) {
                ClientPlayNetworking.send(new P2PIdentityPayloads.GoogleIdentityC2SPayload(""));
                return;
            }
            CompletableFuture.supplyAsync(() -> GoogleAuthService.getInstance().getValidIdToken())
                    .thenAccept(idToken -> client.execute(() -> {
                        if (client.getConnection() != null
                                && ClientPlayNetworking.canSend(P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE)) {
                            String tokenToSend = (SharedWorldConfig.getInstance().isEnabled() && idToken != null)
                                    ? idToken
                                    : "";
                            ClientPlayNetworking.send(new P2PIdentityPayloads.GoogleIdentityC2SPayload(tokenToSend));
                        }
                    }));
        });

        // Receive auto-shared per-world Google Drive folder ID from Host without overwriting user's own root folder
        ClientPlayNetworking.registerGlobalReceiver(
                P2PIdentityPayloads.SharedWorldGrantS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
                        String folderId = payload.worldFolderId() != null ? payload.worldFolderId().trim() : "";
                        String safeName = SharedWorldManager.sanitizeName(payload.worldName());
                        if (!folderId.isBlank() && folderId.matches("^[a-zA-Z0-9_-]{8,128}$") && !safeName.isEmpty()) {
                            cfg.putLinkedDriveWorld(safeName, folderId);
                            cfg.setLastSharedWorldName(safeName);
                            if (context.client().player != null) {
                                context.client().player.sendSystemMessage(Component.literal(
                                        "§b☁ [FabricP2P] §aLinked Shared Drive World '§f" + safeName
                                                + "§a'! Open §bShared Worlds§a to view or host it."
                                ));
                            }
                        }
                    });
                }
        );

        // Automatically clean up UPnP/UDP/Tunnel & sync shared world when leaving
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            P2PNetworkManager.getInstance().onWorldDisconnect();
        });
    }
}
