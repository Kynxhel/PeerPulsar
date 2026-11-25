package com.kynxhel.peerpulsar.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Pure-Java TCP Tunnel Relay Client (compatible with bore protocol).
 * Used as an automatic fallback when the host is behind CGNAT, Symmetric NAT,
 * or a router without UPnP support.
 */
public class TunnelRelayService {
    private static final int MAX_CONCURRENT_STREAMS = 64;

    private static TunnelRelayService instance;

    private final AtomicLong tunnelGeneration = new AtomicLong(0);
    private final AtomicInteger pendingOrActiveStreams = new AtomicInteger(0);
    private volatile boolean active = false;
    private volatile String remoteHost = "bore.pub";
    private volatile int remotePort = -1;
    private volatile int activeRelayControlPort = 7835;
    private volatile Socket controlSocket = null;
    private volatile Consumer<String> unexpectedDisconnectHandler = null;
    private final Set<Socket> activeProxySockets = ConcurrentHashMap.newKeySet();

    private TunnelRelayService() {}

    public static synchronized TunnelRelayService getInstance() {
        if (instance == null) {
            instance = new TunnelRelayService();
        }
        return instance;
    }

    public void setUnexpectedDisconnectHandler(Consumer<String> handler) {
        this.unexpectedDisconnectHandler = handler;
    }

    public boolean isActive() {
        return active;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    /**
     * Quick reachability & latency check across configured relay servers.
     * Returns latency in ms of the first reachable relay, or -1 if unreachable.
     */
    public static long testRelayLatencyMs() {
        List<NetworkEndpoints.HostPort> relays = NetworkEndpoints.getOrderedRelays(PeerPulsarConfig.get());
        for (NetworkEndpoints.HostPort relay : relays) {
            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                InetAddress pinned = NetworkEndpoints.resolvePinnedPublicAddress(relay.host(), false);
                socket.connect(new InetSocketAddress(pinned, relay.port()), 2500);
                return Math.max(1L, System.currentTimeMillis() - start);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /**
     * Opens a public TCP tunnel on the configured relay server forwarding to 127.0.0.1:<localPort>.
     */
    public CompletableFuture<TunnelStartResult> startTunnelAsync(int localPort) {
        return startTunnelAsync(localPort, null);
    }

    public CompletableFuture<TunnelStartResult> startTunnelAsync(int localPort, Consumer<String> statusNotifier) {
        return CompletableFuture.supplyAsync(() -> {
            stopTunnel();
            final long gen = tunnelGeneration.incrementAndGet();
            List<NetworkEndpoints.HostPort> relays = NetworkEndpoints.getOrderedRelays(PeerPulsarConfig.get());
            String lastError = "No relay endpoints configured";

            for (int idx = 0; idx < relays.size(); idx++) {
                if (tunnelGeneration.get() != gen) {
                    return new TunnelStartResult(false, null, -1, "Tunnel startup cancelled");
                }
                NetworkEndpoints.HostPort relay = relays.get(idx);
                if (idx > 0 && statusNotifier != null) {
                    statusNotifier.accept("Relay unreachable, trying next…");
                }

                Socket ctrl = null;
                boolean handedOff = false;
                try {
                    final InetAddress pinnedRelayAddr = NetworkEndpoints.resolvePinnedPublicAddress(relay.host(), false);
                    ctrl = new Socket();
                    ctrl.connect(new InetSocketAddress(pinnedRelayAddr, relay.port()), 5000);
                    ctrl.setSoTimeout(10000);
                    ctrl.setTcpNoDelay(true);
                    ctrl.setKeepAlive(true);

                    sendJsonFrame(ctrl.getOutputStream(), "{\"Hello\":0}");

                    String response = readJsonFrame(ctrl.getInputStream());
                    if (response == null) {
                        closeQuietly(ctrl);
                        lastError = "Relay server closed connection";
                        continue;
                    }

                    JsonElement elem = JsonParser.parseString(response);
                    if (!elem.isJsonObject() || !elem.getAsJsonObject().has("Hello")) {
                        closeQuietly(ctrl);
                        lastError = "Unexpected relay response: " + response;
                        continue;
                    }

                    int assignedPort = elem.getAsJsonObject().get("Hello").getAsInt();
                    ctrl.setSoTimeout(60_000);

                    final Socket committedCtrl = ctrl;
                    final String relayHost = relay.host();
                    final int relayControlPort = relay.port();
                    synchronized (this) {
                        if (tunnelGeneration.get() != gen) {
                            closeQuietly(ctrl);
                            return new TunnelStartResult(false, null, -1, "Tunnel startup cancelled");
                        }
                        this.controlSocket = committedCtrl;
                        this.remoteHost = relayHost;
                        this.remotePort = assignedPort;
                        this.activeRelayControlPort = relayControlPort;
                        this.active = true;
                        handedOff = true;

                        Thread controlThread = new Thread(
                                () -> runControlLoop(committedCtrl, pinnedRelayAddr, relayControlPort, localPort),
                                "PeerPulsar-Tunnel-Control"
                        );
                        controlThread.setDaemon(true);
                        controlThread.start();
                    }

                    PeerPulsar.LOGGER.info("[P2P-Tunnel] Tunnel established via {} -> 127.0.0.1:{}",
                            relay.formatted(), localPort);

                    return new TunnelStartResult(true, relayHost, assignedPort, null);
                } catch (Exception e) {
                    lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    PeerPulsar.LOGGER.warn("[P2P-Tunnel] Relay {} failed ({}); trying next if available", relay.formatted(), lastError);
                    if (!handedOff) {
                        closeQuietly(ctrl);
                    }
                    if (tunnelGeneration.get() == gen && handedOff) {
                        stopTunnelIfGeneration(gen);
                    }
                }
            }
            return new TunnelStartResult(false, null, -1, lastError);
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private void runControlLoop(Socket ctrl, InetAddress pinnedRelayAddr, int relayControlPort, int localPort) {
        String dropReason = null;
        try {
            InputStream in = ctrl.getInputStream();
            while (active && !ctrl.isClosed()) {
                String frame = readJsonFrame(in);
                if (frame == null) {
                    dropReason = "Relay closed control stream";
                    break;
                }
                if (frame.equals("\"Heartbeat\"")) {
                    continue;
                }
                try {
                    JsonElement elem = JsonParser.parseString(frame);
                    if (elem.isJsonObject()) {
                        JsonObject obj = elem.getAsJsonObject();
                        if (obj.has("Connection")) {
                            String uuid = obj.get("Connection").getAsString();
                            spawnConnectionProxy(ctrl, pinnedRelayAddr, relayControlPort, uuid, localPort);
                        } else if (obj.has("Error")) {
                            PeerPulsar.LOGGER.warn("[P2P-Tunnel] Relay error: {}", obj.get("Error").getAsString());
                        }
                    }
                } catch (Exception e) {
                    PeerPulsar.LOGGER.warn("[P2P-Tunnel] Failed to parse relay frame '{}': {}", frame, e.getMessage());
                }
            }
        } catch (IOException e) {
            if (active) {
                dropReason = e.getMessage();
                PeerPulsar.LOGGER.warn("[P2P-Tunnel] Control connection closed: {}", e.getMessage());
            }
        } finally {
            boolean wasUnexpected;
            synchronized (this) {
                wasUnexpected = active && (this.controlSocket == ctrl);
                if (this.controlSocket == ctrl) {
                    this.active = false;
                    this.controlSocket = null;
                    this.remotePort = -1;
                    for (Socket s : activeProxySockets) {
                        closeQuietly(s);
                    }
                    activeProxySockets.clear();
                }
            }
            closeQuietly(ctrl);
            if (wasUnexpected) {
                Consumer<String> handler = this.unexpectedDisconnectHandler;
                if (handler != null) {
                    handler.accept(dropReason != null ? dropReason : "Tunnel relay disconnected");
                }
            }
        }
    }

    private void spawnConnectionProxy(Socket expectedCtrl, InetAddress pinnedRelayAddr, int relayControlPort, String uuid, int localPort) {
        int current = pendingOrActiveStreams.incrementAndGet();
        if (current > MAX_CONCURRENT_STREAMS) {
            pendingOrActiveStreams.decrementAndGet();
            PeerPulsar.LOGGER.warn("[P2P-Tunnel] Max concurrent streams ({}) reached; dropping connection {}",
                    MAX_CONCURRENT_STREAMS, uuid);
            return;
        }
        Thread proxyThread = new Thread(() -> {
            Socket remoteSock = null;
            Socket localSock = null;
            try {
                if (!active || this.controlSocket != expectedCtrl) {
                    return;
                }

                remoteSock = new Socket();
                remoteSock.connect(new InetSocketAddress(pinnedRelayAddr, relayControlPort), 5000);
                remoteSock.setTcpNoDelay(true);
                JsonObject acceptObj = new JsonObject();
                acceptObj.addProperty("Accept", uuid);
                sendJsonFrame(remoteSock.getOutputStream(), acceptObj.toString());

                localSock = new Socket();
                localSock.connect(new InetSocketAddress("127.0.0.1", localPort), 3000);
                localSock.setTcpNoDelay(true);

                if (!active || this.controlSocket != expectedCtrl) {
                    return;
                }

                activeProxySockets.add(remoteSock);
                activeProxySockets.add(localSock);

                if (!active || this.controlSocket != expectedCtrl) {
                    return;
                }

                final Socket rSock = remoteSock;
                final Socket lSock = localSock;

                Thread upstream = new Thread(() -> {
                    try {
                        pipeBytes(lSock, rSock);
                    } finally {
                        try {
                            if (!rSock.isClosed()) {
                                rSock.setSoTimeout(3000);
                            }
                        } catch (Exception ignored) {}
                    }
                }, "PeerPulsar-Proxy-Up");
                upstream.setDaemon(true);
                upstream.start();

                pipeBytes(rSock, lSock);
                upstream.join(1500);
            } catch (Exception e) {
                PeerPulsar.LOGGER.debug("[P2P-Tunnel] Proxy stream ended ({}): {}", uuid, e.getMessage());
            } finally {
                pendingOrActiveStreams.decrementAndGet();
                closeQuietly(remoteSock);
                closeQuietly(localSock);
                if (remoteSock != null) activeProxySockets.remove(remoteSock);
                if (localSock != null) activeProxySockets.remove(localSock);
            }
        }, "PeerPulsar-Proxy-Down");
        proxyThread.setDaemon(true);
        try {
            proxyThread.start();
        } catch (Throwable t) {
            pendingOrActiveStreams.decrementAndGet();
            PeerPulsar.LOGGER.warn("[P2P-Tunnel] Failed to start proxy thread for {}: {}", uuid, t.getMessage());
        }
    }

    private static void pipeBytes(Socket from, Socket to) {
        byte[] buffer = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
            try {
                if (!to.isClosed() && !to.isOutputShutdown()) {
                    to.shutdownOutput();
                }
            } catch (IOException ignored) {}
        } catch (IOException ignored) {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void stopTunnelIfGeneration(long expectedGen) {
        synchronized (this) {
            if (tunnelGeneration.get() != expectedGen) {
                return;
            }
            stopTunnel();
        }
    }

    public void stopTunnel() {
        tunnelGeneration.incrementAndGet();
        synchronized (this) {
            this.active = false;
            this.remotePort = -1;

            Socket ctrl = this.controlSocket;
            this.controlSocket = null;
            closeQuietly(ctrl);

            for (Socket s : activeProxySockets) {
                closeQuietly(s);
            }
            activeProxySockets.clear();
        }
    }

    private static void sendJsonFrame(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.write(0);
        out.flush();
    }

    private static String readJsonFrame(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0) {
                return baos.toString(StandardCharsets.UTF_8);
            }
            baos.write(b);
            if (baos.size() > 65536) {
                throw new IOException("Relay frame too large");
            }
        }
        return null;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    public record TunnelStartResult(boolean success, String remoteHost, int remotePort, String error) {}
}
