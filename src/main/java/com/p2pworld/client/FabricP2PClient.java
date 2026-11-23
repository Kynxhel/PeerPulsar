package com.p2pworld.client;

import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.network.P2PIdentityPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

public class FabricP2PClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricP2PMod.LOGGER.info("[FabricP2P-Client] Neon P2P Engine initialized (MC 26.3).");
        P2PNetworkManager.getInstance();

        // Send our Google identity to the Host on join (for anti-spoof nickname protection & auto Drive share)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String email = SharedWorldConfig.getInstance().getGoogleUserEmail();
            if (ClientPlayNetworking.canSend(P2PIdentityPayloads.GoogleIdentityC2SPayload.TYPE)) {
                ClientPlayNetworking.send(new P2PIdentityPayloads.GoogleIdentityC2SPayload(email != null ? email : ""));
            }
        });

        // Receive auto-shared Google Drive folder ID from Host and save it seamlessly
        ClientPlayNetworking.registerGlobalReceiver(
                P2PIdentityPayloads.SharedWorldGrantS2CPayload.TYPE,
                (payload, context) -> {
                    context.client().execute(() -> {
                        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
                        if (payload.driveFolderId() != null && !payload.driveFolderId().isBlank()) {
                            cfg.setGoogleDriveFolderId(payload.driveFolderId());
                            cfg.setLastSharedWorldName(payload.worldName());
                            if (!cfg.isEnabled()) {
                                cfg.setEnabled(true);
                            }
                            if (context.client().player != null) {
                                context.client().player.sendSystemMessage(Component.literal(
                                        "§b☁ [FabricP2P] §aAuto-linked to Shared Google Drive World '§f"
                                                + payload.worldName() + "§a'! You can host it anytime from Shared Worlds."
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
