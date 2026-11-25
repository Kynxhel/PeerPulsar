package com.kynxhel.peerpulsar.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import com.kynxhel.peerpulsar.security.BruteForceGuard;
import com.kynxhel.peerpulsar.security.JoinApprovalManager;
import com.kynxhel.peerpulsar.security.OfflineAuth;
import com.kynxhel.peerpulsar.security.RoomKeys;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class P2PNetworkManager {
    private static final AtomicLong NET_IO_THREAD_SEQ = new AtomicLong(0);
    public static final ExecutorService NETWORK_IO_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PeerPulsar-NetIO-" + NET_IO_THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

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
    private volatile boolean offlineModeEnabled = false;
    private volatile int hostedPort = 25565;
    private volatile String hostedPublicHost = null;
    private volatile int hostedPublicPort = -1;
    private volatile String hostedBaseRoomCode = null;
    private volatile String hostedRoomCode = null;
    private volatile String hostRoomKey = null;
    private volatile boolean hostRequiresRoomKey = false;
    private volatile String pendingJoinKey = null;
    private volatile ActiveHostingMode activeHostingMode = ActiveHostingMode.NONE;
    private volatile ConnectionMode selectedConnectionMode = ConnectionMode.DIRECT_UPNP;
    private volatile boolean userManuallySelectedMode = false;
    private volatile String lastErrorMessage = null;
    private volatile GameType selectedGameType = GameType.SURVIVAL;
    private volatile boolean allowCheats = false;
    private volatile boolean useDefaultPort = PeerPulsarConfig.get().isUseDefaultPort();
    private volatile boolean joinApprovalEnabled = PeerPulsarConfig.get().isJoinApprovalEnabled();
    private final AtomicLong hostGeneration = new AtomicLong(0);
    private final AtomicBoolean suppressNextUdpClientStop = new AtomicBoolean(false);
    private volatile ActiveUdpJoinInfo activeUdpJoinInfo = null;
    private volatile boolean udpReconnecting = false;

    public record ActiveUdpJoinInfo(String roomCode, String signalingServer, String roomKey) {}

    private P2PNetworkManager() {
        TunnelRelayService.getInstance().setUnexpectedDisconnectHandler(this::onTunnelDroppedUnexpectedly);
        OfflineAuth.setHostContext(new OfflineAuth.HostSecurityContext() {
            @Override
            public boolean isHostingActiveOrStarting() {
                return p2pActive || starting;
            }

            @Override
            public boolean isOfflineAuthEnabled() {
                return offlineModeEnabled;
            }

            @Override
            public boolean isRoomKeyRequired() {
                return hostRequiresRoomKey;
            }

            @Override
            public boolean isJoinApprovalEnabled() {
                return joinApprovalEnabled;
            }

            @Override
            public String getHostRoomKey() {
                return hostRoomKey;
            }

            @Override
            public String resolveRemoteIp(Connection connection) {
                if (connection == null || connection.getRemoteAddress() == null) return null;
                if (connection.getRemoteAddress() instanceof InetSocketAddress inet) {
                    if (activeHostingMode == ActiveHostingMode.UDP_HOLE_PUNCH) {
                        String mapped = UdpHolePunchService.getInstance().resolvePeerIpForLocalPort(inet.getPort());
                        if (mapped != null && !mapped.isBlank()) {
                            return mapped;
                        }
                    }
                    return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
                }
                return connection.getRemoteAddress().toString();
            }

            @Override
            public ExecutorService getAsyncExecutor() {
                return NETWORK_IO_EXECUTOR;
            }

            @Override
            public void onPreApprovedJoin(String playerName) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && playerName != null) {
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.translatable(
                                    "screen.peerpulsar.host.msg_preapproved_join", playerName));
                        }
                    });
                }
            }
        });
    }

    public static synchronized P2PNetworkManager getInstance() {
        if (instance == null) {
            instance = new P2PNetworkManager();
        }
        return instance;
    }

    private void onTunnelDroppedUnexpectedly(String reason) {
        if (!p2pActive || activeHostingMode != ActiveHostingMode.TUNNEL_RELAY) {
            return;
        }
        final long droppedGen = this.hostGeneration.get();
        PeerPulsar.LOGGER.warn("[P2P-26.3] Tunnel Relay disconnected unexpectedly: {}", reason);
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (!this.p2pActive || this.activeHostingMode != ActiveHostingMode.TUNNEL_RELAY
                        || this.hostGeneration.get() != droppedGen) {
                    return;
                }
                stopP2PHost(client);
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal(
                            "§c⚠ [PeerPulsar] §fTunnel Relay connection dropped (" + reason + "). Start P2P again to reconnect."
                    ));
                }
            });
        }
    }

    public boolean isP2PActive() {
        return p2pActive;
    }

    public boolean isHostGenerationActive(long gen) {
        return this.p2pActive && this.hostGeneration.get() == gen;
    }

    public long getHostGeneration() {
        return this.hostGeneration.get();
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

    public String getHostRoomKey() {
        return hostRoomKey;
    }

    public boolean isHostRequiresRoomKey() {
        return hostRequiresRoomKey;
    }

    public boolean isJoinApprovalEnabled() {
        return joinApprovalEnabled;
    }

    public void setJoinApprovalEnabled(boolean enabled) {
        this.joinApprovalEnabled = enabled;
        PeerPulsarConfig.get().setJoinApprovalEnabled(enabled);
    }

    /**
     * Rotates the active session Room Key without restarting the tunnel/UDP/server or kicking connected players.
     * Immediately invalidates the previous code.
     */
    public synchronized String rotateRoomKey() {
        if (!p2pActive || hostedBaseRoomCode == null || hostedBaseRoomCode.isEmpty() || !hostRequiresRoomKey) {
            return null;
        }
        String newKey = RoomKeys.generate();
        this.hostRoomKey = newKey;
        this.hostedRoomCode = hostedBaseRoomCode + "#" + newKey;
        PeerPulsar.LOGGER.info("[P2P-Auth] Room Key rotated; previous room code is now invalid.");
        return this.hostedRoomCode;
    }

    public void setPendingJoinKey(String key) {
        this.pendingJoinKey = RoomKeys.normalize(key);
    }

    public String consumePendingJoinKey() {
        String key = this.pendingJoinKey;
        this.pendingJoinKey = null;
        return key;
    }

    public GameType getSelectedGameType() {
        return selectedGameType;
    }

    public void setSelectedGameType(GameType selectedGameType) {
        this.selectedGameType = selectedGameType;
        if (this.p2pActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getSingleplayerServer() != null) {
                IntegratedServer server = mc.getSingleplayerServer();
                server.execute(() -> server.setDefaultGameType(selectedGameType));
            }
        }
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
        PeerPulsarConfig.get().setUseDefaultPort(useDefaultPort);
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
     * Starts the IntegratedServer as a P2P hosted world, triggers UPnP/UDP/Tunnel and computes room code.
     */
    public synchronized void startP2PHost(Minecraft client, GameType gameType, boolean cheats, int port, boolean offlineMode, Consumer<Boolean> callback) {
        if (this.starting || this.p2pActive) {
            this.lastErrorMessage = "P2P host is already starting or active.";
            callback.accept(false);
            return;
        }

        this.selectedGameType = gameType;
        this.allowCheats = cheats;
        this.hostedPort = port;
        this.offlineModeEnabled = offlineMode;

        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            this.lastErrorMessage = "No singleplayer server running to host.";
            PeerPulsar.LOGGER.error("[P2P] No singleplayer server running to host!");
            callback.accept(false);
            return;
        }

        final long gen = this.hostGeneration.incrementAndGet();
        final boolean preRequiresKey = OfflineAuth.isRoomKeyRequiredForHosting(
                this.selectedConnectionMode == ConnectionMode.LOCAL_LAN,
                PeerPulsarConfig.get().isRequireRoomKeyOnLan()
        );
        final String preSessionKey = preRequiresKey ? RoomKeys.generate() : null;
        this.hostRequiresRoomKey = preRequiresKey;
        this.hostRoomKey = preSessionKey;
        this.starting = true;
        this.lastErrorMessage = null;
        BruteForceGuard.getGlobal().reset();
        JoinApprovalManager.getInstance().clearAll();

        final Consumer<String> failoverStatusNotifier = msg -> client.execute(() -> {
            if (isHostAttemptCancelled(gen, server)) return;
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("§e[PeerPulsar] §f" + msg));
            }
        });

        CompletableFuture.runAsync(() -> {
            final AtomicBoolean publishedOnServerThread = new AtomicBoolean(false);
            boolean didPublish = false;
            boolean didUpnp = false;
            boolean didUdp = false;
            boolean didTunnel = false;
            try {
                // 1. Evaluate network diagnostics unless Local LAN was explicitly chosen
                ConnectionMode requestedMode = this.selectedConnectionMode;
                NetworkDiagnosticsService.DiagnosticReport report = null;
                ConnectionMode mode = requestedMode;

                if (requestedMode != ConnectionMode.LOCAL_LAN) {
                    NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
                    report = diag.hasRecentReport()
                            ? diag.getLastReport()
                            : diag.runDiagnosticsAsync().get(12, TimeUnit.SECONDS);

                    if (isHostAttemptCancelled(gen, server)) {
                        rollbackHostAttempt(client, server, gen, false, false, false, false, callback);
                        return;
                    }

                    syncSelectedModeWithDiagnostics(report);
                    mode = this.selectedConnectionMode;

                    if (mode != ConnectionMode.LOCAL_LAN
                            && report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
                        this.lastErrorMessage = "No internet connection detected (use Local LAN).";
                        rollbackHostAttempt(client, server, gen, false, false, false, false, callback);
                        return;
                    }
                } else if (isHostAttemptCancelled(gen, server)) {
                    rollbackHostAttempt(client, server, gen, false, false, false, false, callback);
                    return;
                }

                PeerPulsar.LOGGER.info("[P2P-26.3] Publishing integrated server on local port {} (OfflineLAN: {}, GameMode: {}, NetMode: {})...",
                        port, offlineMode, gameType, mode);

                // 2. Publish IntegratedServer on client thread (unpublishing first if already published on another port)
                final boolean guestCommands = PeerPulsarConfig.get().isGiveJoinersCommands();
                CompletableFuture<Boolean> publishFuture = new CompletableFuture<>();
                client.execute(() -> {
                    try {
                        if (isHostAttemptCancelled(gen, server)) {
                            publishFuture.complete(false);
                            return;
                        }
                        if (server.isPublished()) {
                            server.unpublishServer();
                        }
                        server.setDefaultGameType(gameType);
                        if (cheats && !server.getWorldData().isAllowCommands()) {
                            server.setWorldAllowCommands(true);
                        }
                        boolean published = server.publishServer(MinecraftServer.MultiplayerScope.LAN, guestCommands, port);
                        if (published) {
                            publishedOnServerThread.set(true);
                        }
                        publishFuture.complete(published);
                    } catch (Throwable t) {
                        publishFuture.completeExceptionally(t);
                    }
                });

                boolean published = publishFuture.get(5, TimeUnit.SECONDS);
                if (!published) {
                    if (!isHostAttemptCancelled(gen, server)) {
                        PeerPulsar.LOGGER.warn("[P2P-26.3] Failed to publish integrated server on port {}", port);
                        this.lastErrorMessage = "Failed to open local server port " + port;
                    }
                    rollbackHostAttempt(client, server, gen, false, false, false, false, callback);
                    return;
                }
                didPublish = true;

                if (isHostAttemptCancelled(gen, server)) {
                    rollbackHostAttempt(client, server, gen, true, false, false, false, callback);
                    return;
                }

                // 3. Activate the chosen connection mode
                String candidateHost;
                int candidatePort;
                String candidateRoomCode;
                ActiveHostingMode candidateMode;

                if (mode == ConnectionMode.LOCAL_LAN) {
                    String localIp = UPnPService.getInstance().refreshLocalIp();
                    candidateHost = localIp;
                    candidatePort = port;
                    candidateRoomCode = RoomCodeHelper.encodeRoomCode(localIp, port);
                    candidateMode = ActiveHostingMode.LOCAL_LAN;
                } else if (mode == ConnectionMode.UDP_HOLE_PUNCH) {
                    PeerPulsar.LOGGER.info("[P2P-26.3] Starting UDP Hole Punching Host -> 127.0.0.1:{}", port);
                    didUdp = true;
                    UdpHolePunchService.HostStartResult udpRes =
                            UdpHolePunchService.getInstance().startHostAsync(port, failoverStatusNotifier).get(12, TimeUnit.SECONDS);

                    if (!udpRes.success()) {
                        if (!isHostAttemptCancelled(gen, server)) {
                            this.lastErrorMessage = "UDP Hole Punch failed: " + udpRes.error();
                        }
                        rollbackHostAttempt(client, server, gen, didPublish, false, didUdp, false, callback);
                        return;
                    }

                    candidateHost = udpRes.publicIp();
                    candidatePort = udpRes.publicPort();
                    candidateRoomCode = udpRes.roomCode();
                    candidateMode = ActiveHostingMode.UDP_HOLE_PUNCH;
                } else {
                    boolean useTunnel = (mode == ConnectionMode.TUNNEL_RELAY);
                    candidateHost = null;
                    candidatePort = port;
                    candidateRoomCode = null;
                    candidateMode = ActiveHostingMode.NONE;

                    if (!useTunnel) {
                        PeerPulsar.LOGGER.info("[P2P-26.3] Attempting Direct P2P via UPnP IGD...");
                        didUpnp = true;
                        boolean upnpOk = UPnPService.getInstance().openPort(port, "PeerPulsar P2P World (26.3)");
                        if (isHostAttemptCancelled(gen, server)) {
                            rollbackHostAttempt(client, server, gen, didPublish, didUpnp, false, false, callback);
                            return;
                        }
                        if (upnpOk) {
                            String extIp = (report != null && report.publicIp() != null)
                                    ? report.publicIp()
                                    : UPnPService.getInstance().getExternalIp();
                            candidateHost = extIp;
                            candidatePort = port;
                            candidateRoomCode = RoomCodeHelper.encodeRoomCode(extIp, port);
                            candidateMode = ActiveHostingMode.DIRECT_UPNP;
                        } else {
                            didUpnp = false;
                            PeerPulsar.LOGGER.warn("[P2P-26.3] UPnP mapping failed at runtime — switching to automatic Tunnel Fallback!");
                            useTunnel = true;
                        }
                    }

                    if (useTunnel) {
                        PeerPulsar.LOGGER.info("[P2P-26.3] Starting Tunnel Relay -> 127.0.0.1:{}", port);
                        didTunnel = true;
                        TunnelRelayService.TunnelStartResult tunnelRes =
                                TunnelRelayService.getInstance().startTunnelAsync(port, failoverStatusNotifier).get(12, TimeUnit.SECONDS);

                        if (!tunnelRes.success()) {
                            if (!isHostAttemptCancelled(gen, server)) {
                                this.lastErrorMessage = "Tunnel relay failed: " + tunnelRes.error();
                            }
                            rollbackHostAttempt(client, server, gen, didPublish, didUpnp, false, didTunnel, callback);
                            return;
                        }

                        candidateHost = tunnelRes.remoteHost();
                        candidatePort = tunnelRes.remotePort();
                        candidateRoomCode = RoomCodeHelper.encodeRoomCode(tunnelRes.remoteHost(), tunnelRes.remotePort());
                        candidateMode = ActiveHostingMode.TUNNEL_RELAY;
                    }
                }

                final boolean finalRequiresKey = OfflineAuth.isRoomKeyRequiredForHosting(
                        candidateMode == ActiveHostingMode.LOCAL_LAN,
                        PeerPulsarConfig.get().isRequireRoomKeyOnLan()
                );
                final String finalRoomKey = finalRequiresKey
                        ? (preSessionKey != null ? preSessionKey : RoomKeys.generate())
                        : null;
                final String baseRoomCode = candidateRoomCode;
                if (finalRoomKey != null && candidateRoomCode != null && !candidateRoomCode.isEmpty()) {
                    candidateRoomCode = candidateRoomCode + "#" + finalRoomKey;
                }

                boolean cancelledBeforeCommit = false;
                synchronized (this) {
                    if (isHostAttemptCancelled(gen, server)) {
                        cancelledBeforeCommit = true;
                    } else if (candidateMode == ActiveHostingMode.TUNNEL_RELAY && !TunnelRelayService.getInstance().isActive()) {
                        if (this.lastErrorMessage == null) {
                            this.lastErrorMessage = "Tunnel relay disconnected during startup";
                        }
                        cancelledBeforeCommit = true;
                    } else if (candidateMode == ActiveHostingMode.UDP_HOLE_PUNCH && !UdpHolePunchService.getInstance().isHostActive()) {
                        if (this.lastErrorMessage == null) {
                            this.lastErrorMessage = "UDP host stopped during startup";
                        }
                        cancelledBeforeCommit = true;
                    } else {
                        this.hostedPublicHost = candidateHost;
                        this.hostedPublicPort = candidatePort;
                        this.hostedBaseRoomCode = baseRoomCode;
                        this.hostedRoomCode = candidateRoomCode;
                        this.hostRequiresRoomKey = finalRequiresKey;
                        this.hostRoomKey = finalRoomKey;
                        this.activeHostingMode = candidateMode;
                        this.p2pActive = true;
                        this.starting = false;
                    }
                }

                if (cancelledBeforeCommit) {
                    rollbackHostAttempt(client, server, gen, didPublish, didUpnp, didUdp, didTunnel, callback);
                    return;
                }

                PeerPulsar.LOGGER.info("[P2P-26.3] World hosted via {} (roomKeyRequired={}, localPort={}).",
                        candidateMode, finalRequiresKey, port);

                final String modeTag = switch (candidateMode) {
                    case UDP_HOLE_PUNCH -> "§b[UDP Hole Punch]";
                    case TUNNEL_RELAY -> "§d[Tunnel Relay]";
                    case LOCAL_LAN -> "§b[Local LAN]";
                    default -> "§a[Direct P2P]";
                };
                client.execute(() -> {
                    if (!isHostGenerationActive(gen)) {
                        return;
                    }
                    if (client.player != null) {
                        client.player.sendSystemMessage(Component.literal(
                                "§b✦ [PeerPulsar] " + modeTag + " §fWorld is live! Copy the Room Code from the Start P2P screen."));
                    }
                    callback.accept(true);
                });
            } catch (Exception e) {
                PeerPulsar.LOGGER.error("[P2P-26.3] Error starting P2P host", e);
                if (this.hostGeneration.get() == gen) {
                    this.lastErrorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                rollbackHostAttempt(client, server, gen, didPublish || publishedOnServerThread.get(), didUpnp, didUdp, didTunnel, callback);
            }
        }, NETWORK_IO_EXECUTOR);
    }

    private boolean isHostAttemptCancelled(long gen, IntegratedServer server) {
        return this.hostGeneration.get() != gen || !server.isRunning();
    }

    private void rollbackHostAttempt(
            Minecraft client,
            IntegratedServer server,
            long gen,
            boolean didPublish,
            boolean didUpnp,
            boolean didUdp,
            boolean didTunnel,
            Consumer<Boolean> callback
    ) {
        final boolean ownedAttempt;
        final boolean cleanupLeakedServices;
        final long rollbackGen;
        synchronized (this) {
            if (this.hostGeneration.get() == gen) {
                rollbackGen = this.hostGeneration.incrementAndGet();
                this.p2pActive = false;
                this.starting = false;
                this.activeHostingMode = ActiveHostingMode.NONE;
                this.hostedPublicHost = null;
                this.hostedPublicPort = -1;
                this.hostedBaseRoomCode = null;
                this.hostedRoomCode = null;
                this.hostRequiresRoomKey = false;
                this.hostRoomKey = null;
                ownedAttempt = true;
                cleanupLeakedServices = true;
            } else {
                rollbackGen = -1L;
                ownedAttempt = false;
                // If stopP2PHost/onWorldDisconnect ran while this attempt was starting services
                // and no newer host attempt is active/starting, still tear down the newly started services.
                cleanupLeakedServices = !this.p2pActive && !this.starting;
            }
        }
        if (cleanupLeakedServices) {
            if (didUpnp) {
                UPnPService.getInstance().closePortAsync();
            }
            if (didUdp) {
                UdpHolePunchService.getInstance().stopHost();
            }
            if (didTunnel) {
                TunnelRelayService.getInstance().stopTunnel();
            }
        }
        client.execute(() -> {
            if (didPublish && ownedAttempt && server.isRunning()
                    && this.hostGeneration.get() == rollbackGen) {
                try {
                    if (server.isPublished()) {
                        server.unpublishServer();
                    }
                    server.setUsesAuthentication(true);
                } catch (Exception ignored) {}
            }
            callback.accept(false);
        });
    }

    /**
     * Prevents the next onWorldDisconnect() call from tearing down an active UDP client proxy
     * (used when switching directly from singleplayer into a UDP Shared World).
     */
    public void suppressNextClientProxyDisconnectCleanup() {
        this.suppressNextUdpClientStop.set(true);
    }

    public void setActiveUdpJoinInfo(ActiveUdpJoinInfo info) {
        this.activeUdpJoinInfo = info;
    }

    public ActiveUdpJoinInfo getActiveUdpJoinInfo() {
        return activeUdpJoinInfo;
    }

    public boolean isUdpReconnecting() {
        return udpReconnecting;
    }

    /**
     * Cleanly disconnects from the current UDP P2P world and reconnects using {@code targetEngine}
     * with the saved room code and Room Key.
     */
    public void reconnectUdpWithTransport(UdpTransportMode targetEngine) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || targetEngine == null || !targetEngine.isConcrete()) return;
        mc.execute(() -> {
            ActiveUdpJoinInfo info = this.activeUdpJoinInfo;
            if (this.udpReconnecting || info == null) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.translatable("screen.peerpulsar.transport.cannot_reconnect"));
                }
                return;
            }
            this.udpReconnecting = true;
            PeerPulsar.LOGGER.info("[P2P-UDP] Reconnecting with transport engine {}...", targetEngine.displayName());
            UdpHolePunchService.getInstance().stopClientProxy();
            suppressNextClientProxyDisconnectCleanup();
            mc.disconnectFromWorld(Component.translatable("screen.peerpulsar.transport.reconnecting", targetEngine.displayName()));

            UdpHolePunchService.getInstance()
                    .startClientProxyAsync(info.roomCode(), info.signalingServer(), targetEngine)
                    .whenComplete((res, err) -> mc.execute(() -> {
                        this.udpReconnecting = false;
                        if (err == null && res != null && res.success()) {
                            this.activeUdpJoinInfo = info;
                            setPendingJoinKey(info.roomKey());
                            ServerData serverData = new ServerData(
                                    "UDP P2P (" + info.roomCode() + ")",
                                    "127.0.0.1:" + res.localPort(),
                                    ServerData.Type.OTHER
                            );
                            ConnectScreen.startConnecting(
                                    new TitleScreen(),
                                    mc,
                                    new ServerAddress("127.0.0.1", res.localPort()),
                                    serverData,
                                    false,
                                    null
                            );
                        } else {
                            this.activeUdpJoinInfo = null;
                            String reason = (res != null && res.error() != null)
                                    ? res.error()
                                    : (err != null && err.getMessage() != null ? err.getMessage() : "Reconnect failed");
                            mc.setScreenAndShow(new DisconnectedScreen(
                                    new TitleScreen(),
                                    Component.translatable("screen.peerpulsar.transport.reconnect_failed_title"),
                                    Component.literal(reason)
                            ));
                        }
                    }));
        });
    }

    /**
     * Unpublishes IntegratedServer, closes UPnP mappings and resets active P2P state.
     */
    public void stopP2PHost(Minecraft client) {
        synchronized (this) {
            if (!p2pActive && !starting) return;
            this.hostGeneration.incrementAndGet();
            this.p2pActive = false;
            this.starting = false;
            this.activeHostingMode = ActiveHostingMode.NONE;
            this.hostedPublicHost = null;
            this.hostedPublicPort = -1;
            this.hostedBaseRoomCode = null;
            this.hostedRoomCode = null;
            this.hostRequiresRoomKey = false;
            this.hostRoomKey = null;
        }

        BruteForceGuard.getGlobal().reset();
        JoinApprovalManager.getInstance().clearAll();

        PeerPulsar.LOGGER.info("[P2P-26.3] Stopping P2P host and cleaning up UPnP/UDP/Tunnel...");

        UPnPService.getInstance().closePortAsync();
        TunnelRelayService.getInstance().stopTunnel();
        UdpHolePunchService.getInstance().stopHost();

        if (client != null) {
            IntegratedServer server = client.getSingleplayerServer();
            if (server != null) {
                if (server.isPublished()) {
                    try {
                        server.unpublishServer();
                    } catch (Exception e) {
                        PeerPulsar.LOGGER.warn("[P2P-26.3] Failed to unpublish server cleanly: {}", e.getMessage());
                    }
                }
                try {
                    server.setUsesAuthentication(true);
                } catch (Exception e) {
                    PeerPulsar.LOGGER.warn("[P2P-26.3] Failed to restore Mojang authentication flag: {}", e.getMessage());
                }
            }
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("§c■ [PeerPulsar] §fP2P hosting stopped and network resources cleared."));
            }
        }
    }

    /**
     * Called automatically when the player disconnects from a world so UPnP/Tunnel doesn't leak.
     */
    public void onWorldDisconnect() {
        if (!suppressNextUdpClientStop.getAndSet(false)) {
            UdpHolePunchService.getInstance().stopClientProxy();
        }
        if (!this.udpReconnecting) {
            this.activeUdpJoinInfo = null;
        }
        boolean wasActiveOrStarting;
        synchronized (this) {
            this.hostGeneration.incrementAndGet();
            wasActiveOrStarting = this.p2pActive || this.starting;
            if (wasActiveOrStarting) {
                this.p2pActive = false;
                this.starting = false;
                this.activeHostingMode = ActiveHostingMode.NONE;
                this.hostedPublicHost = null;
                this.hostedPublicPort = -1;
                this.hostedBaseRoomCode = null;
                this.hostedRoomCode = null;
                this.hostRequiresRoomKey = false;
                this.hostRoomKey = null;
            }
        }
        if (wasActiveOrStarting) {
            BruteForceGuard.getGlobal().reset();
            JoinApprovalManager.getInstance().clearAll();
            PeerPulsar.LOGGER.info("[P2P-26.3] World disconnected — auto-cleaning UPnP, UDP & Tunnel.");
            UPnPService.getInstance().closePortAsync();
            TunnelRelayService.getInstance().stopTunnel();
            UdpHolePunchService.getInstance().stopHost();
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
        }, NETWORK_IO_EXECUTOR);
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
                motd = extractComponentText(root.get("description"));
            }
        } catch (Exception ignored) {}
        return new PingResult(true, latency, "ONLINE", json, online, max, motd.trim());
    }

    private static String extractComponentText(JsonElement el) {
        StringBuilder sb = new StringBuilder();
        appendComponentText(el, sb, 0);
        return sb.toString();
    }

    private static void appendComponentText(JsonElement el, StringBuilder sb, int depth) {
        if (el == null || el.isJsonNull() || depth > 16) return;
        if (el.isJsonPrimitive()) {
            sb.append(el.getAsString());
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                appendComponentText(child, sb, depth + 1);
            }
        } else if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
                sb.append(obj.get("text").getAsString());
            }
            if (obj.has("extra")) {
                appendComponentText(obj.get("extra"), sb, depth + 1);
            }
        }
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
