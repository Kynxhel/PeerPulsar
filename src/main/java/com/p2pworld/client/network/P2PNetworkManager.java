package com.p2pworld.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.shared.SharedWorldManager;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class P2PNetworkManager {
    private static P2PNetworkManager instance;

    public enum ActiveHostingMode {
        NONE,
        DIRECT_UPNP,
        UDP_HOLE_PUNCH,
        TUNNEL_RELAY,
        LOCAL_LAN
    }

    public enum ConnectionMode {
        DIRECT_UPNP,
        UDP_HOLE_PUNCH,
        TUNNEL_RELAY,
        LOCAL_LAN
    }

    private volatile boolean p2pActive = false;
    private volatile boolean starting = false;
    private volatile boolean offlineModeEnabled = true;
    private volatile int hostedPort = 25565;
    private volatile String hostedPublicHost = null;
    private volatile int hostedPublicPort = -1;
    private volatile String hostedRoomCode = null;
    private volatile ActiveHostingMode activeHostingMode = ActiveHostingMode.NONE;
    private volatile ConnectionMode selectedConnectionMode = ConnectionMode.DIRECT_UPNP;
    private volatile boolean userManuallySelectedMode = false;
    private volatile String lastErrorMessage = null;
    private volatile GameType selectedGameType = GameType.SURVIVAL;
    private volatile boolean allowCheats = false;
    private volatile boolean useDefaultPort = true;

    private P2PNetworkManager() {}

    public static synchronized P2PNetworkManager getInstance() {
        if (instance == null) {
            instance = new P2PNetworkManager();
        }
        return instance;
    }

    public boolean isP2PActive() {
        return p2pActive;
    }

    public boolean isStarting() {
        return starting;
    }

    public boolean isOfflineModeEnabled() {
        return offlineModeEnabled;
    }

    public void setOfflineModeEnabled(boolean offlineModeEnabled) {
        this.offlineModeEnabled = offlineModeEnabled;
    }

    public int getHostedPort() {
        return hostedPort;
    }

    public String getHostedPublicHost() {
        return hostedPublicHost != null ? hostedPublicHost : UPnPService.getInstance().getExternalIp();
    }

    public int getHostedPublicPort() {
        return hostedPublicPort > 0 ? hostedPublicPort : hostedPort;
    }

    public ActiveHostingMode getActiveHostingMode() {
        return activeHostingMode;
    }

    public ConnectionMode getSelectedConnectionMode() {
        return selectedConnectionMode;
    }

    public void setSelectedConnectionMode(ConnectionMode mode, boolean manual) {
        this.selectedConnectionMode = mode;
        if (manual) {
            this.userManuallySelectedMode = true;
        }
    }

    public boolean isModeSupported(ConnectionMode mode, NetworkDiagnosticsService.DiagnosticReport report) {
        return switch (mode) {
            case DIRECT_UPNP -> report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.DIRECT_SUPPORTED;
            case UDP_HOLE_PUNCH -> report.udpHolePunchSupported();
            case TUNNEL_RELAY -> report.tunnelAvailable();
            case LOCAL_LAN -> true;
        };
    }

    /**
     * Returns the fastest supported connection mode for the current network.
     * Direct P2P (TCP) > UDP Hole Punch (Direct UDP through CGNAT, 0ms relay) > Tunnel Relay > Local LAN.
     */
    public ConnectionMode getRecommendedMode(NetworkDiagnosticsService.DiagnosticReport report) {
        if (isModeSupported(ConnectionMode.DIRECT_UPNP, report)) {
            return ConnectionMode.DIRECT_UPNP;
        }
        if (isModeSupported(ConnectionMode.UDP_HOLE_PUNCH, report)) {
            return ConnectionMode.UDP_HOLE_PUNCH;
        }
        if (isModeSupported(ConnectionMode.TUNNEL_RELAY, report)) {
            return ConnectionMode.TUNNEL_RELAY;
        }
        return ConnectionMode.LOCAL_LAN;
    }

    /**
     * Syncs the selected connection mode after diagnostics complete if the user
     * hasn't manually picked a valid mode yet.
     */
    public void syncSelectedModeWithDiagnostics(NetworkDiagnosticsService.DiagnosticReport report) {
        if (!userManuallySelectedMode || !isModeSupported(selectedConnectionMode, report)) {
            this.selectedConnectionMode = getRecommendedMode(report);
        }
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public String getHostedRoomCode() {
        return hostedRoomCode;
    }

    public GameType getSelectedGameType() {
        return selectedGameType;
    }

    public void setSelectedGameType(GameType selectedGameType) {
        this.selectedGameType = selectedGameType;
    }

    public boolean isAllowCheats() {
        return allowCheats;
    }

    public void setAllowCheats(boolean allowCheats) {
        this.allowCheats = allowCheats;
    }

    public boolean isUseDefaultPort() {
        return useDefaultPort;
    }

    public void setUseDefaultPort(boolean useDefaultPort) {
        this.useDefaultPort = useDefaultPort;
    }

    public int getOnlinePlayerCount(Minecraft client) {
        if (client == null) return 0;
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return 0;
        return server.getPlayerCount();
    }

    public int getMaxPlayerCount(Minecraft client) {
        if (client == null) return 8;
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return 8;
        return server.getMaxPlayers();
    }

    public int allocatePort() {
        if (useDefaultPort) {
            return 25565;
        }
        return 25566 + new Random().nextInt(4433);
    }

    /**
     * Starts the IntegratedServer as a P2P hosted world, triggers UPnP and computes room code.
     */
    public void startP2PHost(Minecraft client, GameType gameType, boolean cheats, int port, boolean offlineMode, Consumer<Boolean> callback) {
        this.selectedGameType = gameType;
        this.allowCheats = cheats;
        this.hostedPort = port;
        this.offlineModeEnabled = offlineMode;

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            FabricP2PMod.LOGGER.error("[P2P] No singleplayer server running to host!");
            callback.accept(false);
            return;
        }

        this.starting = true;
        this.lastErrorMessage = null;

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Ensure network diagnostics have been evaluated (except for Local LAN)
                NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
                NetworkDiagnosticsService.DiagnosticReport report = diag.hasRecentReport()
                        ? diag.getLastReport()
                        : diag.runDiagnosticsAsync().get(12, TimeUnit.SECONDS);
                syncSelectedModeWithDiagnostics(report);

                ConnectionMode mode = this.selectedConnectionMode;

                if (mode != ConnectionMode.LOCAL_LAN
                        && report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
                    this.lastErrorMessage = "No internet connection detected (use Local LAN).";
                    this.starting = false;
                    client.execute(() -> callback.accept(false));
                    return;
                }

                FabricP2PMod.LOGGER.info("[P2P-26.3] Publishing integrated server on local port {} (OfflineLAN: {}, GameMode: {}, NetMode: {})...",
                        port, offlineMode, gameType, mode);

                // 2. Publish IntegratedServer on client thread and wait for result
                CompletableFuture<Boolean> publishFuture = new CompletableFuture<>();
                client.execute(() -> {
                    try {
                        server.setDefaultGameType(gameType);
                        boolean published = server.isPublished()
                                || server.publishServer(MinecraftServer.MultiplayerScope.LAN, cheats, port);
                        publishFuture.complete(published);
                    } catch (Throwable t) {
                        publishFuture.completeExceptionally(t);
                    }
                });

                boolean published = publishFuture.get(5, TimeUnit.SECONDS);
                if (!published) {
                    FabricP2PMod.LOGGER.warn("[P2P-26.3] Failed to publish integrated server on port {}", port);
                    this.lastErrorMessage = "Failed to open local server port " + port;
                    this.starting = false;
                    client.execute(() -> callback.accept(false));
                    return;
                }

                // 3. Activate the chosen connection mode
                if (mode == ConnectionMode.LOCAL_LAN) {
                    String localIp = UPnPService.getInstance().getLocalIp();
                    this.hostedPublicHost = localIp;
                    this.hostedPublicPort = port;
                    this.hostedRoomCode = RoomCodeHelper.encodeRoomCode(localIp, port);
                    this.activeHostingMode = ActiveHostingMode.LOCAL_LAN;
                } else if (mode == ConnectionMode.UDP_HOLE_PUNCH) {
                    FabricP2PMod.LOGGER.info("[P2P-26.3] Starting Essential-style UDP Hole Punching Host -> 127.0.0.1:{}", port);
                    UdpHolePunchService.HostStartResult udpRes =
                            UdpHolePunchService.getInstance().startHostAsync(port).get(10, TimeUnit.SECONDS);

                    if (!udpRes.success()) {
                        this.lastErrorMessage = "UDP Hole Punch failed: " + udpRes.error();
                        this.starting = false;
                        client.execute(() -> {
                            try {
                                if (server.isPublished()) server.unpublishServer();
                            } catch (Exception ignored) {}
                            callback.accept(false);
                        });
                        return;
                    }

                    this.hostedPublicHost = udpRes.publicIp();
                    this.hostedPublicPort = udpRes.publicPort();
                    this.hostedRoomCode = udpRes.roomCode();
                    this.activeHostingMode = ActiveHostingMode.UDP_HOLE_PUNCH;
                } else {
                    boolean useTunnel = (mode == ConnectionMode.TUNNEL_RELAY);
                    if (!useTunnel) {
                        FabricP2PMod.LOGGER.info("[P2P-26.3] Attempting Direct P2P via UPnP IGD...");
                        boolean upnpOk = UPnPService.getInstance().openPort(port, "Minecraft P2P World (26.3)");
                        if (upnpOk) {
                            String extIp = report.publicIp() != null ? report.publicIp() : UPnPService.getInstance().getExternalIp();
                            this.hostedPublicHost = extIp;
                            this.hostedPublicPort = port;
                            this.hostedRoomCode = RoomCodeHelper.encodeRoomCode(extIp, port);
                            this.activeHostingMode = ActiveHostingMode.DIRECT_UPNP;
                        } else {
                            FabricP2PMod.LOGGER.warn("[P2P-26.3] UPnP mapping failed at runtime — switching to automatic Tunnel Fallback!");
                            useTunnel = true;
                        }
                    }

                    if (useTunnel) {
                        FabricP2PMod.LOGGER.info("[P2P-26.3] Starting Tunnel Relay (bore.pub) -> 127.0.0.1:{}", port);
                        TunnelRelayService.TunnelStartResult tunnelRes =
                                TunnelRelayService.getInstance().startTunnelAsync(port).get(10, TimeUnit.SECONDS);

                        if (!tunnelRes.success()) {
                            this.lastErrorMessage = "Tunnel relay failed: " + tunnelRes.error();
                            this.starting = false;
                            client.execute(() -> {
                                try {
                                    if (server.isPublished()) server.unpublishServer();
                                } catch (Exception ignored) {}
                                callback.accept(false);
                            });
                            return;
                        }

                        this.hostedPublicHost = tunnelRes.remoteHost();
                        this.hostedPublicPort = tunnelRes.remotePort();
                        this.hostedRoomCode = RoomCodeHelper.encodeRoomCode(tunnelRes.remoteHost(), tunnelRes.remotePort());
                        this.activeHostingMode = ActiveHostingMode.TUNNEL_RELAY;
                    }
                }

                this.p2pActive = true;
                this.starting = false;

                // If Shared Cloud Worlds is enabled for this world, publish live lock & heartbeat
                SharedWorldManager.getInstance().onP2PHostStarted(client, this.hostedRoomCode, this.activeHostingMode);

                FabricP2PMod.LOGGER.info("[P2P-26.3] World hosted via {}! Room Code: {} -> {}:{}",
                        activeHostingMode, hostedRoomCode, hostedPublicHost, hostedPublicPort);

                final String modeTag = switch (activeHostingMode) {
                    case UDP_HOLE_PUNCH -> "§b[UDP Hole Punch]";
                    case TUNNEL_RELAY -> "§d[Tunnel Relay]";
                    case LOCAL_LAN -> "§b[Local LAN]";
                    default -> "§a[Direct P2P]";
                };
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.literal(
                                "§b✦ [FabricP2P] " + modeTag + " §fWorld is live! Room Code: §b§l" + hostedRoomCode));
                    }
                    callback.accept(true);
                });
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[P2P-26.3] Error starting P2P host", e);
                this.lastErrorMessage = e.getMessage();
                this.starting = false;
                client.execute(() -> callback.accept(false));
            }
        });
    }

    /**
     * Unpublishes IntegratedServer, closes UPnP mappings and resets active P2P state.
     */
    public void stopP2PHost(Minecraft client) {
        if (!p2pActive && !starting) return;

        FabricP2PMod.LOGGER.info("[P2P-26.3] Stopping P2P host and cleaning up UPnP/UDP/Tunnel...");
        UPnPService.getInstance().closePortAsync();
        TunnelRelayService.getInstance().stopTunnel();
        UdpHolePunchService.getInstance().stopHost();
        SharedWorldManager.getInstance().onP2PHostStopped(client);

        if (client != null) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null && server.isPublished()) {
                try {
                    server.unpublishServer();
                } catch (Exception e) {
                    FabricP2PMod.LOGGER.warn("[P2P-26.3] Failed to unpublish server cleanly: {}", e.getMessage());
                }
            }
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("§c■ [FabricP2P] §fP2P hosting stopped and network resources cleared."));
            }
        }

        this.p2pActive = false;
        this.starting = false;
        this.activeHostingMode = ActiveHostingMode.NONE;
        this.hostedPublicHost = null;
        this.hostedPublicPort = -1;
        this.hostedRoomCode = null;
    }

    /**
     * Called automatically when the player disconnects from a world so UPnP/Tunnel doesn't leak.
     */
    public void onWorldDisconnect() {
        UdpHolePunchService.getInstance().stopClientProxy();
        SharedWorldManager.getInstance().onP2PHostStopped(Minecraft.getInstance());
        if (p2pActive || starting) {
            FabricP2PMod.LOGGER.info("[P2P-26.3] World disconnected — auto-cleaning UPnP, UDP & Tunnel.");
            UPnPService.getInstance().closePortAsync();
            TunnelRelayService.getInstance().stopTunnel();
            UdpHolePunchService.getInstance().stopHost();
            this.p2pActive = false;
            this.starting = false;
            this.activeHostingMode = ActiveHostingMode.NONE;
            this.hostedPublicHost = null;
            this.hostedPublicPort = -1;
            this.hostedRoomCode = null;
        }
    }

    /**
     * Performs an asynchronous Minecraft 26.3 Server List Ping handshake & parses telemetry JSON.
     */
    public CompletableFuture<PingResult> pingTarget(String host, int port) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3500);
                socket.setSoTimeout(3500);

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                byte[] handshake = createHandshakePacket(host, port);
                out.write(handshake);

                // Status Request Packet (id = 0x00)
                out.writeByte(0x01);
                out.writeByte(0x00);
                out.flush();

                readVarInt(in); // packet length
                int packetId = readVarInt(in);
                long latency = System.currentTimeMillis() - startTime;

                if (packetId == 0x00) {
                    int jsonLen = readVarInt(in);
                    if (jsonLen > 0 && jsonLen < 1_048_576) {
                        byte[] jsonBytes = new byte[jsonLen];
                        in.readFully(jsonBytes);
                        String json = new String(jsonBytes, StandardCharsets.UTF_8);
                        return parseStatusJson(latency, json);
                    }
                }
                return new PingResult(true, latency, "ONLINE", "", -1, -1, "");
            } catch (Exception e) {
                return new PingResult(false, -1, "UNREACHABLE", null, -1, -1, e.getMessage() != null ? e.getMessage() : "Connection timed out");
            }
        });
    }

    private PingResult parseStatusJson(long latency, String json) {
        int online = -1;
        int max = -1;
        String motd = "";
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject players = root.getAsJsonObject("players");
                if (players.has("online")) online = players.get("online").getAsInt();
                if (players.has("max")) max = players.get("max").getAsInt();
            }
            if (root.has("description")) {
                JsonElement desc = root.get("description");
                if (desc.isJsonPrimitive()) {
                    motd = desc.getAsString();
                } else if (desc.isJsonObject() && desc.getAsJsonObject().has("text")) {
                    motd = desc.getAsJsonObject().get("text").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return new PingResult(true, latency, "ONLINE", json, online, max, motd.trim());
    }

    private byte[] createHandshakePacket(String host, int port) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(b);
            handshake.writeByte(0x00);
            writeVarInt(handshake, SharedConstants.getProtocolVersion());
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.writeShort(port);
            writeVarInt(handshake, 1); // Next state: Status

            byte[] bytes = b.toByteArray();
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(frame);
            writeVarInt(out, bytes.length);
            out.write(bytes);
            return frame.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new RuntimeException("VarInt is too big");
            }
        } while ((read & 0b10000000) != 0);
        return result;
    }

    public record PingResult(
            boolean success,
            long latencyMs,
            String status,
            String rawJson,
            int onlinePlayers,
            int maxPlayers,
            String motd
    ) {}
}
