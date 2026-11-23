package com.p2pworld.client;

import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class FabricP2PClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricP2PMod.LOGGER.info("[Atarca-Client] Neon P2P Engine initialized (MC 26.3).");
        P2PNetworkManager.getInstance();

        // Automatically clean up UPnP/UDP/Tunnel when leaving a world
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            P2PNetworkManager.getInstance().onWorldDisconnect();
        });
    }
}
