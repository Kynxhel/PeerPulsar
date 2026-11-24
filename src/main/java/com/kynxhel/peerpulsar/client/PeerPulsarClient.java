package com.kynxhel.peerpulsar.client;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.client.gui.JoinApprovalOverlay;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.client.network.TunnelRelayService;
import com.kynxhel.peerpulsar.client.network.UPnPService;
import com.kynxhel.peerpulsar.client.network.UdpHolePunchService;
import com.kynxhel.peerpulsar.client.security.OfflineAuthClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class PeerPulsarClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PeerPulsar.LOGGER.info("[PeerPulsar-Client] P2P Engine initialized (MC 26.3).");
        P2PNetworkManager.getInstance();
        OfflineAuthClient.registerClientSide();
        JoinApprovalOverlay.register();

        // Automatically clean up UPnP/UDP/Tunnel when leaving a world
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PeerPulsar.LOGGER.info("[PeerPulsar-Client] Disconnected from world; cleaning up UPnP/UDP/Tunnel.");
            P2PNetworkManager.getInstance().onWorldDisconnect();
        });

        // Best-effort cleanup if the JVM exits unexpectedly while hosting
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boolean wasMapped = UPnPService.getInstance().isMapped();
                int mappedPort = UPnPService.getInstance().getMappedPort();
                UPnPService.getInstance().closePort();
                if (wasMapped) {
                    PeerPulsar.LOGGER.info("[PeerPulsar-Shutdown] Closed UPnP mapping on port {} (stillMapped={}).",
                            mappedPort, UPnPService.getInstance().isMapped());
                }
            } catch (Exception e) {
                PeerPulsar.LOGGER.warn("[PeerPulsar-Shutdown] UPnP cleanup failed: {}", e.getMessage());
            }
            try {
                TunnelRelayService.getInstance().stopTunnel();
            } catch (Exception ignored) {}
            try {
                UdpHolePunchService.getInstance().stopHost();
                UdpHolePunchService.getInstance().stopClientProxy();
            } catch (Exception ignored) {}
        }, "PeerPulsar-Shutdown-Cleanup"));
    }
}
