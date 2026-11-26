package com.kynxhel.peerpulsar.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import com.kynxhel.peerpulsar.security.SignalCrypto;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ServerConnection;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 3-Engine UDP Hole Punching P2P transport:
 * - RUDP (Light / Pure): low overhead, 30ms tick, zero extra framing for clean low-ping links.
 * - KCP (PvP / Aggressive): 10ms tick, 64-bit SACK, fast-resend after 2 out-of-order skips, 256-pkt window.
 * - QUIC (Kwik TLS 1.3): full transport encryption, pacing/congestion control for high ping/loss/mobile links.
 * Includes connect-time Auto engine probing and live mid-game telemetry + switch recommendations.
 */
public class UdpHolePunchService {
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String QUIC_ALPN = "peerpulsar-mc";
    private static final int PROTO_VERSION = 4;

    private static final byte MAGIC_0 = 0x50; // 'P'
    private static final byte MAGIC_1 = 0x32; // '2'
    private static final int HEADER_SIZE = 14;
    private static final int MAX_PAYLOAD = 1180;
    private static final int MAX_REORDER_PACKETS = 512;
    private static final int MAX_RETRANSMITS = 18;
    private static final int MAX_HOST_SESSIONS = 64;
    private static final long SESSION_IDLE_TIMEOUT_MS = 20_000L;

    private static final byte TYPE_PUNCH = 1;
    private static final byte TYPE_PUNCH_ACK = 2;
    private static final byte TYPE_DATA = 3;
    private static final byte TYPE_ACK = 4;
    private static final byte TYPE_FIN = 5;
    private static final byte TYPE_KEEPALIVE = 6;
    private static final byte TYPE_PROBE = 7;
    private static final byte TYPE_PROBE_ACK = 8;
    private static final byte TYPE_SELECT = 9;
    private static final byte TYPE_SELECT_ACK = 10;

    private static final byte FLAG_SACK = 0x01;

    private static UdpHolePunchService instance;

    // Host state
    private final Object hostLock = new Object();
    private final AtomicLong hostGeneration = new AtomicLong(0);
    private volatile boolean hostActive = false;
    private volatile String hostRoomCode = null;
    private volatile String hostPublicIp = null;
    private volatile int hostPublicPort = -1;
    private volatile String hostSignalingUrl = NetworkEndpoints.DEFAULT_SIGNALING_URL;
    private volatile DatagramSocket hostUdpSocket = null;
    private volatile HttpURLConnection hostSignalConn = null;
    private volatile ScheduledExecutorService hostScheduler = null;
    private volatile ExecutorService punchExecutor = null;
    private volatile EphemeralTlsCert hostQuicCert = null;
    private volatile DemuxDatagramSocket hostQuicDemux = null;
    private volatile ServerConnector hostQuicServer = null;
    private static final long QUIC_PEER_IDLE_EVICT_MS = 45_000L;
    private final Map<InetSocketAddress, Long> hostQuicPeers = new ConcurrentHashMap<>();
    private final Set<AutoCloseable> hostQuicResources = ConcurrentHashMap.newKeySet();
    private final Map<Integer, PeerSession> hostSessions = new ConcurrentHashMap<>();
    private final Map<Integer, String> hostLocalPortToPeerIp = new ConcurrentHashMap<>();

    // Client join proxy state
    private final Object clientLock = new Object();
    private final AtomicLong clientGeneration = new AtomicLong(0);
    private volatile boolean clientActive = false;
    private volatile DatagramSocket clientUdpSocket = null;
    private volatile ServerSocket clientLocalServer = null;
    private volatile HttpURLConnection clientSignalConn = null;
    private volatile PeerSession clientSession = null;
    private volatile ScheduledExecutorService clientScheduler = null;
    private volatile DemuxDatagramSocket clientQuicDemux = null;
    private volatile QuicClientConnection clientQuicConn = null;
    private final Set<AutoCloseable> clientQuicResources = ConcurrentHashMap.newKeySet();

    // Client live telemetry & switch advisor
    private volatile UdpTransportMode clientActiveEngine = UdpTransportMode.RUDP;
    private volatile boolean clientHostSupportsQuic = false;
    private volatile boolean clientForceManualSelection = false;
    private volatile long clientConnectedAtMs = 0L;
    private volatile double clientRttMs = -1.0;
    private volatile double clientJitterMs = 0.0;
    private volatile double clientLossPct = 0.0;
    private volatile long lastQuicPacketsSent = 0L;
    private volatile long lastQuicPacketsLost = 0L;
    private final Map<Integer, Long> pendingClientProbes = new ConcurrentHashMap<>();
    private final AtomicInteger probeSeqGen = new AtomicInteger(1);
    private final AtomicInteger liveProbeWindowSent = new AtomicInteger(0);
    private final AtomicInteger liveProbeWindowRecv = new AtomicInteger(0);
    private final TransportSwitchAdvisor switchAdvisor = new TransportSwitchAdvisor();
    private volatile Consumer<TransportSwitchAdvisor.SwitchRecommendation> switchRecommendationListener = null;

    private UdpHolePunchService() {}

    public static synchronized UdpHolePunchService getInstance() {
        if (instance == null) {
            instance = new UdpHolePunchService();
        }
        return instance;
    }

    public boolean isHostActive() {
        return hostActive;
    }

    public boolean isClientActive() {
        return clientActive;
    }

    public String getHostRoomCode() {
        return hostRoomCode;
    }

    public String getHostPublicIp() {
        return hostPublicIp;
    }

    public int getHostPublicPort() {
        return hostPublicPort;
    }

    public String getHostSignalingUrl() {
        return hostSignalingUrl;
    }

    public boolean isHostQuicReady() {
        return hostQuicCert != null && hostQuicServer != null;
    }

    public void setSwitchRecommendationListener(Consumer<TransportSwitchAdvisor.SwitchRecommendation> listener) {
        this.switchRecommendationListener = listener;
    }

    public void dismissSwitchRecommendation() {
        switchAdvisor.dismiss(System.currentTimeMillis());
    }

    public TransportTelemetry getClientTransportTelemetry() {
        if (!clientActive) {
            return null;
        }
        return new TransportTelemetry(
                clientActiveEngine,
                clientHostSupportsQuic,
                clientRttMs,
                clientJitterMs,
                clientLossPct,
                clientConnectedAtMs
        );
    }

    /**
     * Resolves the remote peer's real IP address for an active UDP Hole Punch TCP proxy local port.
     */
    public String resolvePeerIpForLocalPort(int localPort) {
        return hostLocalPortToPeerIp.get(localPort);
    }

    // ==================== HOST SIDE ====================

    public CompletableFuture<HostStartResult> startHostAsync(int localMcPort) {
        return startHostAsync(localMcPort, null);
    }

    public CompletableFuture<HostStartResult> startHostAsync(int localMcPort, Consumer<String> statusNotifier) {
        return CompletableFuture.supplyAsync(() -> {
            stopHost();
            final long gen = hostGeneration.incrementAndGet();
            DatagramSocket udp = null;
            DemuxDatagramSocket quicDemux = null;
            ServerConnector quicConnector = null;
            boolean handedOff = false;
            try {
                final DatagramSocket boundUdp = new DatagramSocket();
                udp = boundUdp;
                boundUdp.setReceiveBufferSize(512 * 1024);
                boundUdp.setSendBufferSize(512 * 1024);

                PeerPulsarConfig cfg = PeerPulsarConfig.get();
                List<NetworkEndpoints.HostPort> stunServers = NetworkEndpoints.getOrderedStun(cfg);
                InetSocketAddress mapped = null;
                NetworkEndpoints.HostPort chosenStun = null;
                for (int i = 0; i < stunServers.size(); i++) {
                    NetworkEndpoints.HostPort stun = stunServers.get(i);
                    if (i > 0 && statusNotifier != null) {
                        statusNotifier.accept("STUN unreachable, trying next…");
                    }
                    mapped = queryStunOnSocket(boundUdp, stun.host(), stun.port());
                    if (mapped != null) {
                        chosenStun = stun;
                        break;
                    }
                }
                if (mapped == null || chosenStun == null) {
                    boundUdp.close();
                    return new HostStartResult(false, null, null, -1, "STUN mapping failed");
                }

                List<String> signalingList = NetworkEndpoints.getOrderedSignaling(cfg);
                String chosenSignaling = null;
                for (int i = 0; i < signalingList.size(); i++) {
                    String candidate = signalingList.get(i);
                    if (i > 0 && statusNotifier != null) {
                        statusNotifier.accept("Signaling unreachable, trying next…");
                    }
                    if (NetworkEndpoints.pingHttpsMs(candidate) >= 0) {
                        chosenSignaling = candidate;
                        break;
                    }
                }
                if (chosenSignaling == null) {
                    chosenSignaling = signalingList.isEmpty() ? NetworkEndpoints.DEFAULT_SIGNALING_URL : signalingList.get(0);
                }

                InetAddress stunAddr = resolveIpv4(chosenStun.host());
                final int keepaliveStunPort = chosenStun.port();

                // Prepare QUIC (Kwik) ServerConnector over demuxed UDP socket
                EphemeralTlsCert quicCert = null;
                try {
                    quicCert = EphemeralTlsCert.generate();
                    quicDemux = new DemuxDatagramSocket(boundUdp);
                    ServerConnectionConfig quicCfg = ServerConnectionConfig.builder()
                            .maxIdleTimeoutInSeconds(30)
                            .maxOpenPeerInitiatedBidirectionalStreams(4)
                            .maxConnectionBufferSize(4L * 1024L * 1024L)
                            .maxBidirectionalStreamBufferSize(1024L * 1024L)
                            .retryRequired(false)
                            .build();
                    quicConnector = ServerConnector.builder()
                            .withSocket(quicDemux)
                            .withKeyStore(quicCert.keyStore(), quicCert.alias(), quicCert.password())
                            .withConfiguration(quicCfg)
                            .withLogger(new NullLogger())
                            .build();
                    quicConnector.registerApplicationProtocol(QUIC_ALPN, (protocol, quicConn) -> {
                        InetSocketAddress remoteAddr = (quicConn instanceof ServerConnection sc)
                                ? sc.getInitialRemoteAddress()
                                : null;
                        String remoteIp = (remoteAddr != null && remoteAddr.getAddress() != null)
                                ? remoteAddr.getAddress().getHostAddress()
                                : null;
                        return new ApplicationProtocolConnection() {
                            @Override
                            public void acceptPeerInitiatedStream(QuicStream stream) {
                                handleHostQuicStream(gen, localMcPort, remoteIp, stream);
                            }
                        };
                    });
                    quicConnector.start();
                } catch (Exception e) {
                    PeerPulsar.LOGGER.warn("[P2P-UDP] QUIC (Kwik) host engine unavailable, falling back to RUDP/KCP only: {}", e.getMessage());
                    quicCert = null;
                    if (quicConnector != null) {
                        try { quicConnector.close(); } catch (Exception ignored) {}
                        quicConnector = null;
                    }
                    if (quicDemux != null) {
                        quicDemux.close();
                        quicDemux = null;
                    }
                }

                String rawId = randomCode(12);
                String baseRoomCode = "UDP-" + rawId.substring(0, 4) + "-" + rawId.substring(4, 8) + "-" + rawId.substring(8, 12);
                String roomCode = RoomCodeHelper.encodeUdpRoomCode(baseRoomCode, chosenSignaling);
                String topic = SignalCrypto.topicFor(rawId);
                byte[] signalKey = SignalCrypto.keyFor(rawId);
                final String publicIp = mapped.getAddress().getHostAddress();
                final int publicPort = mapped.getPort();
                final String finalSignaling = chosenSignaling;
                final EphemeralTlsCert finalQuicCert = quicCert;

                synchronized (hostLock) {
                    if (hostGeneration.get() != gen) {
                        if (quicConnector != null) try { quicConnector.close(); } catch (Exception ignored) {}
                        if (quicDemux != null) quicDemux.close();
                        boundUdp.close();
                        return new HostStartResult(false, null, null, -1, "Host startup cancelled");
                    }

                    this.hostUdpSocket = boundUdp;
                    this.hostPublicIp = publicIp;
                    this.hostPublicPort = publicPort;
                    this.hostSignalingUrl = finalSignaling;
                    this.hostRoomCode = roomCode;
                    this.hostQuicCert = finalQuicCert;
                    this.hostQuicDemux = quicDemux;
                    this.hostQuicServer = quicConnector;
                    this.hostActive = true;
                    handedOff = true;

                    this.punchExecutor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "PeerPulsar-UDP-PunchOut");
                        t.setDaemon(true);
                        return t;
                    });

                    this.hostScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "PeerPulsar-UDP-Host-Timer");
                        t.setDaemon(true);
                        return t;
                    });

                    // Keep CGNAT UDP port mapping alive using cached STUN IP
                    this.hostScheduler.scheduleAtFixedRate(() -> {
                        if (!hostActive) return;
                        try {
                            sendStunKeepalive(boundUdp, stunAddr, keepaliveStunPort);
                        } catch (Exception ignored) {}
                    }, 12, 12, TimeUnit.SECONDS);

                    // Session-level keepalives so RUDP/KCP clients don't hit 20s idle timeout
                    this.hostScheduler.scheduleAtFixedRate(() -> {
                        if (!hostActive || boundUdp.isClosed()) return;
                        for (PeerSession session : hostSessions.values()) {
                            if (!session.closed.get()) {
                                try {
                                    sendPacket(boundUdp, session.remoteAddr, TYPE_KEEPALIVE, (byte) 0, session.sessionId, 0, null, 0);
                                } catch (Exception ignored) {}
                            }
                        }
                    }, 6, 6, TimeUnit.SECONDS);

                    // 10 ms tick drives aggressive KCP timers & 30 ms throttled RUDP timers + idle cleanup
                    this.hostScheduler.scheduleAtFixedRate(() -> {
                        if (!hostActive) return;
                        for (PeerSession session : hostSessions.values()) {
                            session.tickRetransmitAndTimeout();
                        }
                    }, 10, 10, TimeUnit.MILLISECONDS);

                    // UDP receive loop
                    Thread recvThread = new Thread(() -> runHostUdpLoop(gen, boundUdp, localMcPort), "PeerPulsar-UDP-Host-Recv");
                    recvThread.setDaemon(true);
                    recvThread.start();

                    // Signaling listener loop (ntfy-compatible streaming NDJSON, AES-256-GCM encrypted)
                    Thread signalThread = new Thread(
                            () -> runHostSignalLoop(gen, finalSignaling, topic, signalKey, boundUdp, publicIp, publicPort, finalQuicCert),
                            "PeerPulsar-UDP-Host-Signal"
                    );
                    signalThread.setDaemon(true);
                    signalThread.start();
                }

                PeerPulsar.LOGGER.info("[P2P-UDP] Host ready (RUDP/KCP{}, signaling -> 127.0.0.1:{}).",
                        finalQuicCert != null ? "/QUIC" : "", localMcPort);
                return new HostStartResult(true, roomCode, publicIp, publicPort, null, finalSignaling);
            } catch (Exception e) {
                String errMsg = formatError(e);
                PeerPulsar.LOGGER.error("[P2P-UDP] Failed to start UDP host: {}", errMsg);
                if (!handedOff) {
                    if (quicConnector != null) try { quicConnector.close(); } catch (Exception ignored) {}
                    if (quicDemux != null) quicDemux.close();
                    if (udp != null) try { udp.close(); } catch (Exception ignored) {}
                }
                stopHostIfGeneration(gen);
                return new HostStartResult(false, null, null, -1, errMsg);
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private void handleHostQuicStream(long gen, int localMcPort, String peerIp, QuicStream stream) {
        Thread bridge = new Thread(() -> {
            Socket localTcp = null;
            int localPortUsed = -1;
            try {
                if (!hostActive || hostGeneration.get() != gen) {
                    stream.resetStream(0);
                    return;
                }
                localTcp = new Socket();
                localTcp.connect(new InetSocketAddress("127.0.0.1", localMcPort), 4000);
                localTcp.setTcpNoDelay(true);
                localPortUsed = localTcp.getLocalPort();
                if (localPortUsed > 0 && peerIp != null && !peerIp.isBlank()) {
                    hostLocalPortToPeerIp.put(localPortUsed, peerIp);
                }
                if (!hostActive || hostGeneration.get() != gen) {
                    if (localPortUsed > 0) hostLocalPortToPeerIp.remove(localPortUsed);
                    try { localTcp.close(); } catch (Exception ignored) {}
                    stream.resetStream(0);
                    return;
                }
                final Socket tcp = localTcp;
                final int trackedPort = localPortUsed;
                AutoCloseable cleanupHandle = () -> {
                    if (trackedPort > 0) hostLocalPortToPeerIp.remove(trackedPort);
                    try { tcp.close(); } catch (Exception ignored) {}
                };
                hostQuicResources.add(cleanupHandle);
                bridgeTcpAndQuicStream(tcp, stream, () -> {
                    hostQuicResources.remove(cleanupHandle);
                    try { cleanupHandle.close(); } catch (Exception ignored) {}
                });
            } catch (Exception e) {
                PeerPulsar.LOGGER.warn("[P2P-UDP-QUIC] Failed to bridge stream to local MC server: {}", e.getMessage());
                if (localPortUsed > 0) hostLocalPortToPeerIp.remove(localPortUsed);
                if (localTcp != null) {
                    try { localTcp.close(); } catch (Exception ignored) {}
                }
                try { stream.resetStream(0); } catch (Exception ignored) {}
            }
        }, "PeerPulsar-QUIC-HostBridge");
        bridge.setDaemon(true);
        bridge.start();
    }

    private void runHostSignalLoop(
            long gen,
            String signalingBase,
            String topic,
            byte[] signalKey,
            DatagramSocket udp,
            String myPublicIp,
            int myPublicPort,
            EphemeralTlsCert quicCert
    ) {
        final Set<String> seenMessageIds = new HashSet<>();
        long lastSinceEpoch = Math.max(0L, (System.currentTimeMillis() / 1000L) - 5L);
        while (hostActive && hostGeneration.get() == gen) {
            HttpURLConnection conn = null;
            try {
                conn = NetworkEndpoints.openPinnedPublicHttpsConnection(
                        signalingBase + "/" + topic + "/json?since=" + lastSinceEpoch, 6000, 90_000);
                synchronized (hostLock) {
                    if (!hostActive || hostGeneration.get() != gen) {
                        break;
                    }
                    this.hostSignalConn = conn;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (hostActive && hostGeneration.get() == gen && (line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        try {
                            JsonObject env = JsonParser.parseString(line).getAsJsonObject();
                            if (env.has("time") && env.get("time").isJsonPrimitive()) {
                                lastSinceEpoch = Math.max(lastSinceEpoch, env.get("time").getAsLong());
                            }
                            if (!"message".equals(env.has("event") ? env.get("event").getAsString() : "")) continue;
                            if (env.has("id") && env.get("id").isJsonPrimitive()) {
                                String msgId = env.get("id").getAsString();
                                if (!seenMessageIds.add(msgId)) {
                                    continue;
                                }
                                if (seenMessageIds.size() > 1024) {
                                    seenMessageIds.clear();
                                    seenMessageIds.add(msgId);
                                }
                            }
                            if (!env.has("message") || env.get("message").isJsonNull()) continue;
                            String msgStr = env.get("message").getAsString();
                            JsonObject payload = SignalCrypto.open(signalKey, msgStr);
                            if (payload == null) continue;
                            if (!"join".equals(payload.has("role") ? payload.get("role").getAsString() : "")) continue;

                            String peerId = payload.get("id").getAsString();
                            String peerIp = payload.get("ip").getAsString();
                            int peerPort = payload.get("port").getAsInt();
                            int sessionId = payload.get("sid").getAsInt();

                            if (peerPort < 1 || peerPort > 65535 || peerId.length() > 32 || !isNumericIpLiteral(peerIp)) continue;
                            InetAddress parsedIp = InetAddress.ofLiteral(peerIp);
                            if (parsedIp.isLoopbackAddress() || parsedIp.isMulticastAddress()
                                    || parsedIp.isAnyLocalAddress() || parsedIp.isLinkLocalAddress()) {
                                continue;
                            }

                            if (!hostActive || hostGeneration.get() != gen) {
                                break;
                            }

                            // Build reply safely with JsonObject and encrypt with AES-256-GCM
                            JsonObject replyObj = new JsonObject();
                            replyObj.addProperty("role", "host");
                            replyObj.addProperty("to", peerId);
                            replyObj.addProperty("ip", myPublicIp);
                            replyObj.addProperty("port", myPublicPort);
                            replyObj.addProperty("sid", sessionId);
                            replyObj.addProperty("proto", PROTO_VERSION);
                            if (quicCert != null) {
                                replyObj.addProperty("qcert", quicCert.sha256Hex());
                            }
                            publishSignal(signalingBase, topic, SignalCrypto.seal(signalKey, replyObj));

                            // Actively punch toward the joiner's endpoint using bounded pool
                            InetSocketAddress peerAddr = new InetSocketAddress(parsedIp, peerPort);
                            ExecutorService exec = this.punchExecutor;
                            if (hostActive && hostGeneration.get() == gen && exec != null && !exec.isShutdown()) {
                                exec.submit(() -> {
                                    for (int i = 0; i < 20 && hostActive && hostGeneration.get() == gen && !udp.isClosed(); i++) {
                                        try {
                                            sendPacket(udp, peerAddr, TYPE_PUNCH, (byte) 0, sessionId, 0, null, 0);
                                            Thread.sleep(100);
                                        } catch (Exception ignored) {
                                            break;
                                        }
                                    }
                                });
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                if (hostActive && hostGeneration.get() == gen) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                }
            } finally {
                if (conn != null) {
                    synchronized (hostLock) {
                        if (this.hostSignalConn == conn) {
                            this.hostSignalConn = null;
                        }
                    }
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void runHostUdpLoop(long gen, DatagramSocket udp, int localMcPort) {
        byte[] buf = new byte[2048];
        while (hostActive && hostGeneration.get() == gen && !udp.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udp.receive(pkt);
                if (!hostActive || hostGeneration.get() != gen) {
                    break;
                }
                int len = pkt.getLength();
                InetSocketAddress remoteAddr = (InetSocketAddress) pkt.getSocketAddress();
                if (isStunPacket(buf, len)) {
                    continue;
                }

                // Check exact PeerPulsar P2 control frame before QUIC demuxing (so reconnect works smoothly)
                if (isExactControlFrame(buf, len)) {
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                    bb.position(2);
                    byte type = bb.get();
                    byte flags = bb.get();
                    int sessionId = bb.getInt();
                    int seq = bb.getInt();
                    int payloadLen = bb.getShort() & 0xFFFF;

                    if (type == TYPE_PUNCH) {
                        sendPacket(udp, remoteAddr, TYPE_PUNCH_ACK, (byte) 0, sessionId, 0, null, 0);
                        continue;
                    }
                    if (type == TYPE_PROBE) {
                        byte[] echo = new byte[payloadLen];
                        System.arraycopy(buf, HEADER_SIZE, echo, 0, payloadLen);
                        sendPacket(udp, remoteAddr, TYPE_PROBE_ACK, (byte) 0, sessionId, seq, echo, payloadLen);
                        continue;
                    }
                    if (type == TYPE_SELECT) {
                        UdpTransportMode requested = UdpTransportMode.fromWireId(flags);
                        UdpTransportMode accepted = requested;
                        if (requested == UdpTransportMode.QUIC) {
                            if (hostQuicDemux != null && hostQuicCert != null) {
                                PeerSession old = hostSessions.remove(sessionId);
                                if (old != null) old.close();
                                hostQuicPeers.put(remoteAddr, System.currentTimeMillis());
                                PeerPulsar.LOGGER.info("[P2P-UDP] Peer selected QUIC (Kwik) engine (session={}).", sessionId);
                            } else {
                                accepted = UdpTransportMode.KCP;
                            }
                        }
                        if (accepted == UdpTransportMode.RUDP || accepted == UdpTransportMode.KCP) {
                            hostQuicPeers.remove(remoteAddr);
                            PeerSession session = getOrCreateHostSession(gen, sessionId, udp, remoteAddr, localMcPort, accepted);
                            if (session != null) {
                                session.setEngine(accepted);
                                PeerPulsar.LOGGER.info("[P2P-UDP] Peer selected {} engine (session={}).", accepted.displayName(), sessionId);
                            }
                        }
                        sendPacket(udp, remoteAddr, TYPE_SELECT_ACK, accepted.wireId(), sessionId, 0, null, 0);
                        continue;
                    }
                }

                // Prefer matching PeerPulsar RUDP/KCP session packets BEFORE QUIC IP-rebind fallback so peers switching
                // from QUIC -> KCP/RUDP (or sharing a NAT IP) are never hijacked by stale QUIC peer entries.
                boolean isP2Packet = len >= HEADER_SIZE && buf[0] == MAGIC_0 && buf[1] == MAGIC_1;
                if (isP2Packet) {
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                    bb.position(2);
                    byte type = bb.get();
                    byte flags = bb.get();
                    int sessionId = bb.getInt();
                    int seq = bb.getInt();
                    int payloadLen = bb.getShort() & 0xFFFF;
                    int actualLen = Math.min(payloadLen, len - HEADER_SIZE);

                    PeerSession session = hostSessions.get(sessionId);
                    if (type == TYPE_PUNCH_ACK && session != null) {
                        session.acceptsRemoteAddress(remoteAddr);
                        continue;
                    }
                    if (session == null && type == TYPE_DATA && seq == 0 && !hostQuicPeers.containsKey(remoteAddr)) {
                        session = getOrCreateHostSession(gen, sessionId, udp, remoteAddr, localMcPort, UdpTransportMode.RUDP);
                    }
                    if (session != null && session.acceptsRemoteAddress(remoteAddr)) {
                        handleSessionPacket(session, type, flags, seq, buf, HEADER_SIZE, actualLen);
                        continue;
                    }
                }

                // Route packets from known QUIC peers (or NAT-rebound QUIC IP) to Kwik's DemuxDatagramSocket
                if (hostQuicDemux != null && isQuicPeerAddress(remoteAddr, !isP2Packet)) {
                    hostQuicDemux.enqueueIncoming(buf, 0, len, remoteAddr);
                    continue;
                }
            } catch (IOException e) {
                if (hostActive && hostGeneration.get() == gen) {
                    PeerPulsar.LOGGER.debug("[P2P-UDP] Host recv loop closed: {}", e.getMessage());
                }
                break;
            }
        }
    }

    private boolean isQuicPeerAddress(InetSocketAddress remoteAddr, boolean allowIpRebind) {
        if (remoteAddr == null || hostQuicPeers.isEmpty()) return false;
        long now = System.currentTimeMillis();
        hostQuicPeers.entrySet().removeIf(e -> now - e.getValue() > QUIC_PEER_IDLE_EVICT_MS);
        if (hostQuicPeers.containsKey(remoteAddr)) {
            hostQuicPeers.put(remoteAddr, now);
            return true;
        }
        if (!allowIpRebind) return false;
        InetAddress ip = remoteAddr.getAddress();
        if (ip == null) return false;
        for (InetSocketAddress known : hostQuicPeers.keySet()) {
            if (ip.equals(known.getAddress())) {
                hostQuicPeers.remove(known);
                hostQuicPeers.put(remoteAddr, now);
                return true;
            }
        }
        return false;
    }

    private static boolean isStunPacket(byte[] buf, int len) {
        return len >= 20
                && buf[4] == 0x21 && buf[5] == 0x12 && buf[6] == (byte) 0xA4 && buf[7] == 0x42;
    }

    private static boolean isExactControlFrame(byte[] buf, int len) {
        if (len < HEADER_SIZE || buf[0] != MAGIC_0 || buf[1] != MAGIC_1) return false;
        byte type = buf[2];
        byte flags = buf[3];
        int payloadLen = ((buf[12] & 0xFF) << 8) | (buf[13] & 0xFF);
        if (len != HEADER_SIZE + payloadLen) return false;
        return switch (type) {
            case TYPE_PUNCH -> flags == 0 && payloadLen == 0;
            case TYPE_PROBE -> flags == 0 && payloadLen == 12;
            case TYPE_SELECT -> (flags >= 1 && flags <= 3) && payloadLen == 0;
            default -> false;
        };
    }

    private PeerSession getOrCreateHostSession(
            long gen,
            int sessionId,
            DatagramSocket udp,
            InetSocketAddress remoteAddr,
            int localMcPort,
            UdpTransportMode engine
    ) {
        PeerSession existing = hostSessions.get(sessionId);
        if (existing != null) {
            return existing;
        }
        final PeerSession session;
        synchronized (hostLock) {
            if (!hostActive || hostGeneration.get() != gen) {
                return null;
            }
            existing = hostSessions.get(sessionId);
            if (existing != null) {
                return existing;
            }
            if (hostSessions.size() >= MAX_HOST_SESSIONS) {
                PeerPulsar.LOGGER.warn("[P2P-UDP] Max host sessions ({}) reached; ignoring session {}", MAX_HOST_SESSIONS, sessionId);
                return null;
            }
            PeerPulsar.LOGGER.info("[P2P-UDP] Peer punched through! Session={} Engine={}", sessionId, engine.displayName());
            PeerSession[] ref = new PeerSession[1];
            session = new PeerSession(sessionId, udp, remoteAddr, engine, () -> {
                if (ref[0] != null) {
                    hostSessions.remove(sessionId, ref[0]);
                }
            });
            ref[0] = session;
            hostSessions.put(sessionId, session);
        }

        Thread tcpStarter = new Thread(() -> {
            Socket localTcp = null;
            int localPortUsed = -1;
            try {
                if (!hostActive || hostGeneration.get() != gen) {
                    session.close();
                    return;
                }
                localTcp = new Socket();
                localTcp.connect(new InetSocketAddress("127.0.0.1", localMcPort), 4000);
                localTcp.setTcpNoDelay(true);
                if (!hostActive || hostGeneration.get() != gen) {
                    try { localTcp.close(); } catch (Exception ignored) {}
                    session.close();
                    return;
                }
                session.attachTcpSocket(localTcp, true);
            } catch (Exception e) {
                PeerPulsar.LOGGER.warn("[P2P-UDP] Failed to connect to local MC server: {}", formatError(e));
                if (localTcp != null) {
                    try { localTcp.close(); } catch (Exception ignored) {}
                }
                session.close();
            }
        }, "PeerPulsar-UDP-HostTCP-" + sessionId);
        tcpStarter.setDaemon(true);
        tcpStarter.start();
        return session;
    }

    private void stopHostIfGeneration(long expectedGen) {
        synchronized (hostLock) {
            if (hostGeneration.get() != expectedGen) {
                return;
            }
            stopHost();
        }
    }

    public void stopHost() {
        hostGeneration.incrementAndGet();
        synchronized (hostLock) {
            this.hostActive = false;
            this.hostRoomCode = null;
            this.hostPublicIp = null;
            this.hostPublicPort = -1;
            this.hostQuicCert = null;

            if (hostScheduler != null) {
                hostScheduler.shutdownNow();
                hostScheduler = null;
            }
            if (punchExecutor != null) {
                punchExecutor.shutdownNow();
                punchExecutor = null;
            }
            if (hostSignalConn != null) {
                try { hostSignalConn.disconnect(); } catch (Exception ignored) {}
                hostSignalConn = null;
            }
            for (PeerSession s : hostSessions.values()) {
                s.close();
            }
            hostSessions.clear();
            hostQuicPeers.clear();
            for (AutoCloseable c : hostQuicResources) {
                try { c.close(); } catch (Exception ignored) {}
            }
            hostQuicResources.clear();
            if (hostQuicServer != null) {
                try { hostQuicServer.close(); } catch (Exception ignored) {}
                hostQuicServer = null;
            }
            if (hostQuicDemux != null) {
                try { hostQuicDemux.close(); } catch (Exception ignored) {}
                hostQuicDemux = null;
            }
            hostLocalPortToPeerIp.clear();

            if (hostUdpSocket != null) {
                try { hostUdpSocket.close(); } catch (Exception ignored) {}
                hostUdpSocket = null;
            }
        }
    }

    // ==================== CLIENT (JOINER) SIDE ====================

    public CompletableFuture<ClientProxyResult> startClientProxyAsync(String udpRoomCode) {
        return startClientProxyAsync(udpRoomCode, null, null);
    }

    public CompletableFuture<ClientProxyResult> startClientProxyAsync(String udpRoomCode, String overrideSignalingUrl) {
        return startClientProxyAsync(udpRoomCode, overrideSignalingUrl, null);
    }

    /**
     * Punches a direct UDP P2P link to the host identified by UDP-XXXX-XXXX-XXXX,
     * probes link RTT/jitter/loss, selects RUDP / KCP / QUIC (Kwik), and opens a local TCP proxy on 127.0.0.1:<localPort>.
     */
    public CompletableFuture<ClientProxyResult> startClientProxyAsync(
            String udpRoomCode,
            String overrideSignalingUrl,
            UdpTransportMode overrideTransport
    ) {
        return CompletableFuture.supplyAsync(() -> {
            stopClientProxy();
            final long gen = clientGeneration.incrementAndGet();
            DatagramSocket udp = null;
            ServerSocket localServer = null;
            DemuxDatagramSocket quicDemux = null;
            QuicClientConnection quicConn = null;
            boolean handedOff = false;
            try {
                String baseCode = udpRoomCode == null ? "" : udpRoomCode;
                int hashIdx = baseCode.indexOf('#');
                if (hashIdx >= 0) baseCode = baseCode.substring(0, hashIdx);
                int tildeIdx = baseCode.indexOf('~');
                if (tildeIdx >= 0) baseCode = baseCode.substring(0, tildeIdx);
                String clean = baseCode.toUpperCase().replace("UDP-", "").replace("-", "").trim();
                if (!clean.matches("^[0-9A-Z]{8,32}$")) {
                    return new ClientProxyResult(false, -1, "Invalid UDP room code");
                }
                String signalingBase = NetworkEndpoints.DEFAULT_SIGNALING_URL;
                if (overrideSignalingUrl != null && !overrideSignalingUrl.isBlank()) {
                    String norm = NetworkEndpoints.normalizePublicHttpsUrl(overrideSignalingUrl);
                    if (norm == null) {
                        return new ClientProxyResult(false, -1, "Invalid or non-public signaling URL in room code");
                    }
                    String signalingHost = URI.create(norm).getHost();
                    if (!NetworkEndpoints.resolvesToPublicAddress(signalingHost)) {
                        return new ClientProxyResult(false, -1, "Custom signaling server resolves to a private/local address");
                    }
                    signalingBase = norm;
                }

                String topic = SignalCrypto.topicFor(clean);
                byte[] signalKey = SignalCrypto.keyFor(clean);
                String peerId = randomCode(8);
                int sessionId = ThreadLocalRandom.current().nextInt(100_000, Integer.MAX_VALUE);

                final DatagramSocket boundUdp = new DatagramSocket();
                udp = boundUdp;
                boundUdp.setReceiveBufferSize(512 * 1024);
                boundUdp.setSendBufferSize(512 * 1024);

                InetSocketAddress myMapped = null;
                for (NetworkEndpoints.HostPort stun : NetworkEndpoints.getOrderedStun(PeerPulsarConfig.get())) {
                    myMapped = queryStunOnSocket(boundUdp, stun.host(), stun.port());
                    if (myMapped != null) break;
                }
                if (myMapped == null) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "STUN discovery failed on client");
                }
                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                // Subscribe to signaling BEFORE publishing join request
                CompletableFuture<HostSignalInfo> hostSignalFuture = new CompletableFuture<>();
                CountDownLatch subscribedLatch = new CountDownLatch(1);
                HttpsURLConnection signalConn = NetworkEndpoints.openPinnedPublicHttpsConnection(
                        signalingBase + "/" + topic + "/json", 5000, 9000);
                synchronized (clientLock) {
                    if (clientGeneration.get() != gen) {
                        signalConn.disconnect();
                        boundUdp.close();
                        return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                    }
                    this.clientSignalConn = signalConn;
                }

                Thread sigReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(signalConn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!hostSignalFuture.isDone() && clientGeneration.get() == gen && (line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonObject env = JsonParser.parseString(line).getAsJsonObject();
                                String event = env.has("event") ? env.get("event").getAsString() : "";
                                if ("open".equals(event)) {
                                    subscribedLatch.countDown();
                                    continue;
                                }
                                if (!"message".equals(event) || !env.has("message")) continue;
                                JsonObject payload = SignalCrypto.open(signalKey, env.get("message").getAsString());
                                if (payload == null) continue;
                                if ("host".equals(payload.has("role") ? payload.get("role").getAsString() : "")
                                        && peerId.equals(payload.has("to") ? payload.get("to").getAsString() : "")) {
                                    String hIp = payload.get("ip").getAsString();
                                    int hPort = payload.get("port").getAsInt();
                                    int proto = payload.has("proto") ? payload.get("proto").getAsInt() : 3;
                                    String qcert = (payload.has("qcert") && payload.get("qcert").isJsonPrimitive())
                                            ? payload.get("qcert").getAsString()
                                            : null;
                                    if (hPort >= 1 && hPort <= 65535 && isNumericIpLiteral(hIp)) {
                                        InetAddress hostIpAddr = InetAddress.ofLiteral(hIp);
                                        if (!hostIpAddr.isLoopbackAddress() && !hostIpAddr.isMulticastAddress()
                                                && !hostIpAddr.isAnyLocalAddress() && !hostIpAddr.isLinkLocalAddress()) {
                                            hostSignalFuture.complete(new HostSignalInfo(
                                                    new InetSocketAddress(hostIpAddr, hPort), qcert, proto));
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        hostSignalFuture.completeExceptionally(e);
                    } finally {
                        subscribedLatch.countDown();
                        if (!hostSignalFuture.isDone()) {
                            hostSignalFuture.completeExceptionally(new IOException("Signaling stream closed before host replied"));
                        }
                    }
                }, "PeerPulsar-UDP-ClientSignal");
                sigReader.setDaemon(true);
                sigReader.start();

                HostSignalInfo hostInfo;
                try {
                    subscribedLatch.await(2500, TimeUnit.MILLISECONDS);
                    if (clientGeneration.get() != gen) {
                        boundUdp.close();
                        return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                    }
                    JsonObject joinObj = new JsonObject();
                    joinObj.addProperty("role", "join");
                    joinObj.addProperty("id", peerId);
                    joinObj.addProperty("ip", myMapped.getAddress().getHostAddress());
                    joinObj.addProperty("port", myMapped.getPort());
                    joinObj.addProperty("sid", sessionId);
                    publishSignal(signalingBase, topic, SignalCrypto.seal(signalKey, joinObj));

                    try {
                        hostInfo = hostSignalFuture.get(7, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        boundUdp.close();
                        return new ClientProxyResult(false, -1, "Host signaling timed out");
                    }
                } finally {
                    synchronized (clientLock) {
                        if (this.clientSignalConn == signalConn) {
                            this.clientSignalConn = null;
                        }
                    }
                    try { signalConn.disconnect(); } catch (Exception ignored) {}
                }

                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                // 1. Perform UDP Hole Punching handshake with Host
                InetSocketAddress hostAddr = hostInfo.addr();
                boundUdp.setSoTimeout(150);
                boolean punched = false;
                byte[] rxBuf = new byte[2048];
                long punchDeadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < punchDeadline && clientGeneration.get() == gen) {
                    sendPacket(boundUdp, hostAddr, TYPE_PUNCH, (byte) 0, sessionId, 0, null, 0);
                    try {
                        DatagramPacket rx = new DatagramPacket(rxBuf, rxBuf.length);
                        boundUdp.receive(rx);
                        if (rx.getLength() >= HEADER_SIZE && rxBuf[0] == MAGIC_0 && rxBuf[1] == MAGIC_1) {
                            byte type = rxBuf[2];
                            int rxSid = ByteBuffer.wrap(rxBuf, 4, 4).getInt();
                            if ((type == TYPE_PUNCH || type == TYPE_PUNCH_ACK)
                                    && rxSid == sessionId
                                    && rx.getAddress().equals(hostAddr.getAddress())) {
                                if (rx.getPort() != hostAddr.getPort()) {
                                    hostAddr = new InetSocketAddress(rx.getAddress(), rx.getPort());
                                }
                                sendPacket(boundUdp, hostAddr, TYPE_PUNCH_ACK, (byte) 0, sessionId, 0, null, 0);
                                punched = true;
                                break;
                            }
                        }
                    } catch (IOException ignored) {}
                }

                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }
                if (!punched) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "UDP hole punch timed out (Symmetric NAT?)");
                }

                final InetSocketAddress punchedHostAddr = hostAddr;
                final boolean hostSupportsV4 = hostInfo.protoVersion() >= 4;
                final boolean hostHasQuic = hostInfo.quicCertSha256() != null
                        && hostInfo.quicCertSha256().matches("^[0-9a-fA-F]{64}$");

                // 2. Connect-time 4-packet UDP Probe (~150 ms) to measure RTT, jitter, loss
                TransportSwitchAdvisor.ProbeResult probe = hostSupportsV4
                        ? runInitialProbe(boundUdp, punchedHostAddr, sessionId, gen)
                        : new TransportSwitchAdvisor.ProbeResult(0, 0, 0.0, 0.0, 0.0);

                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                UdpTransportMode configuredMode = PeerPulsarConfig.get().getUdpTransportModeEnum();
                UdpTransportMode requestedMode = (overrideTransport != null && overrideTransport.isConcrete())
                        ? overrideTransport
                        : configuredMode;
                boolean forceManual = (overrideTransport == null && configuredMode.isConcrete());

                UdpTransportMode chosenEngine;
                if (!hostSupportsV4) {
                    chosenEngine = UdpTransportMode.RUDP;
                } else if (requestedMode == UdpTransportMode.AUTO) {
                    chosenEngine = TransportSwitchAdvisor.chooseInitialEngine(probe, hostHasQuic);
                } else if (requestedMode == UdpTransportMode.QUIC && !hostHasQuic) {
                    chosenEngine = UdpTransportMode.KCP;
                } else {
                    chosenEngine = requestedMode;
                }

                // 3. Negotiate selected engine with Host
                if (hostSupportsV4) {
                    UdpTransportMode acked = negotiateEngine(boundUdp, punchedHostAddr, sessionId, chosenEngine, gen);
                    if (acked != null) {
                        chosenEngine = acked;
                    } else {
                        chosenEngine = UdpTransportMode.RUDP;
                    }
                }

                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                // 4. Initialize chosen transport engine (try QUIC if chosen, auto-fallback to KCP if handshake fails)
                QuicStream connectedQuicStream = null;
                if (chosenEngine == UdpTransportMode.QUIC && hostHasQuic) {
                    Thread quicFeedThread = null;
                    try {
                        boundUdp.setSoTimeout(150);
                        quicDemux = new DemuxDatagramSocket(boundUdp);
                        final DemuxDatagramSocket demuxRef = quicDemux;
                        quicFeedThread = new Thread(() -> {
                            byte[] qb = new byte[2048];
                            while (!demuxRef.isClosed() && !boundUdp.isClosed() && clientGeneration.get() == gen) {
                                try {
                                    DatagramPacket p = new DatagramPacket(qb, qb.length);
                                    boundUdp.receive(p);
                                    if (isStunPacket(qb, p.getLength())) continue;
                                    demuxRef.enqueueIncoming(qb, 0, p.getLength(), p.getSocketAddress());
                                } catch (SocketTimeoutException ignored) {
                                    // Periodic wake-up so fallback cleanup can exit cleanly without closing boundUdp
                                } catch (IOException e) {
                                    break;
                                }
                            }
                        }, "PeerPulsar-QUIC-ClientDemux");
                        quicFeedThread.setDaemon(true);
                        quicFeedThread.start();

                        int initRtt = probe.received() > 0 ? Math.max(10, Math.min(500, (int) Math.round(probe.avgRttMs()))) : 100;
                        quicConn = QuicClientConnection.newBuilder()
                                .host(punchedHostAddr.getAddress().getHostAddress())
                                .port(punchedHostAddr.getPort())
                                .applicationProtocol(QUIC_ALPN)
                                .socketFactory(addr -> demuxRef)
                                .customTrustManager(EphemeralTlsCert.pinnedTrustManager(hostInfo.quicCertSha256()))
                                .noServerHostnameVerification()
                                .connectTimeout(Duration.ofSeconds(4))
                                .maxIdleTimeout(Duration.ofSeconds(30))
                                .initialRtt(initRtt)
                                .logger(new NullLogger())
                                .build();
                        quicConn.connect();
                        quicConn.keepAlive(10);
                        connectedQuicStream = quicConn.createStream(true);
                    } catch (Exception qe) {
                        PeerPulsar.LOGGER.warn("[P2P-UDP] QUIC (Kwik) handshake failed ({}), falling back to KCP.", formatError(qe));
                        if (quicConn != null) {
                            try { quicConn.close(); } catch (Exception ignored) {}
                            quicConn = null;
                        }
                        if (quicDemux != null) {
                            quicDemux.close();
                            quicDemux = null;
                        }
                        if (quicFeedThread != null) {
                            try { quicFeedThread.join(350); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        }
                        chosenEngine = UdpTransportMode.KCP;
                        UdpTransportMode reAck = negotiateEngine(boundUdp, punchedHostAddr, sessionId, chosenEngine, gen);
                        if (reAck != null && reAck != UdpTransportMode.QUIC) {
                            chosenEngine = reAck;
                        } else if (reAck == null) {
                            chosenEngine = UdpTransportMode.RUDP;
                        }
                    }
                }

                if (clientGeneration.get() != gen) {
                    if (quicConn != null) try { quicConn.close(); } catch (Exception ignored) {}
                    if (quicDemux != null) quicDemux.close();
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                boundUdp.setSoTimeout(0);
                final ServerSocket boundLocalServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                localServer = boundLocalServer;
                int localProxyPort = boundLocalServer.getLocalPort();

                final UdpTransportMode finalEngine = chosenEngine;
                final QuicClientConnection finalQuicConn = quicConn;
                final QuicStream finalQuicStream = connectedQuicStream;
                final DemuxDatagramSocket finalQuicDemux = quicDemux;

                synchronized (clientLock) {
                    if (clientGeneration.get() != gen) {
                        try { boundLocalServer.close(); } catch (Exception ignored) {}
                        if (finalQuicConn != null) try { finalQuicConn.close(); } catch (Exception ignored) {}
                        if (finalQuicDemux != null) finalQuicDemux.close();
                        try { boundUdp.close(); } catch (Exception ignored) {}
                        return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                    }

                    this.clientUdpSocket = boundUdp;
                    this.clientLocalServer = boundLocalServer;
                    this.clientQuicConn = finalQuicConn;
                    this.clientQuicDemux = finalQuicDemux;
                    this.clientActive = true;
                    this.clientActiveEngine = finalEngine;
                    this.clientHostSupportsQuic = hostHasQuic;
                    this.clientForceManualSelection = forceManual;
                    this.clientConnectedAtMs = System.currentTimeMillis();
                    this.clientRttMs = probe.received() > 0 ? probe.avgRttMs() : -1.0;
                    this.clientJitterMs = probe.jitterMs();
                    this.clientLossPct = probe.lossPct();
                    this.lastQuicPacketsSent = 0L;
                    this.lastQuicPacketsLost = 0L;
                    this.pendingClientProbes.clear();
                    this.liveProbeWindowSent.set(0);
                    this.liveProbeWindowRecv.set(0);
                    this.switchAdvisor.reset();
                    handedOff = true;

                    this.clientScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "PeerPulsar-UDP-Client-Timer");
                        t.setDaemon(true);
                        return t;
                    });

                    if (finalEngine == UdpTransportMode.QUIC && finalQuicConn != null && finalQuicStream != null) {
                        this.clientSession = null;
                        // Poll Kwik connection stats every 2 seconds for live HUD & switch recommendation
                        this.clientScheduler.scheduleAtFixedRate(() -> pollQuicTelemetry(finalQuicConn), 2, 2, TimeUnit.SECONDS);

                        Thread acceptThread = new Thread(() -> {
                            try {
                                boundLocalServer.setSoTimeout(15_000);
                                Socket mcSock = boundLocalServer.accept();
                                try { boundLocalServer.close(); } catch (Exception ignored) {}
                                mcSock.setTcpNoDelay(true);
                                AutoCloseable handle = () -> {
                                    try { mcSock.close(); } catch (Exception ignored) {}
                                };
                                clientQuicResources.add(handle);
                                bridgeTcpAndQuicStream(mcSock, finalQuicStream, () -> {
                                    clientQuicResources.remove(handle);
                                    try { handle.close(); } catch (Exception ignored) {}
                                    stopClientProxy();
                                });
                            } catch (Exception e) {
                                if (clientActive && clientLocalServer == boundLocalServer) {
                                    PeerPulsar.LOGGER.warn("[P2P-UDP-QUIC] Local proxy accept error ({}) — cleaning up.", e.getMessage());
                                    stopClientProxy();
                                }
                            }
                        }, "PeerPulsar-QUIC-ClientAccept");
                        acceptThread.setDaemon(true);
                        acceptThread.start();
                    } else {
                        PeerSession session = new PeerSession(sessionId, boundUdp, punchedHostAddr, finalEngine, this::stopClientProxy);
                        this.clientSession = session;

                        this.clientScheduler.scheduleAtFixedRate(() -> {
                            PeerSession s = clientSession;
                            if (clientActive && s != null) {
                                s.tickRetransmitAndTimeout();
                            }
                        }, 10, 10, TimeUnit.MILLISECONDS);

                        this.clientScheduler.scheduleAtFixedRate(() -> {
                            if (clientActive && !boundUdp.isClosed()) {
                                try {
                                    sendPacket(boundUdp, session.remoteAddr, TYPE_KEEPALIVE, (byte) 0, sessionId, 0, null, 0);
                                } catch (Exception ignored) {}
                            }
                        }, 6, 6, TimeUnit.SECONDS);

                        if (hostSupportsV4) {
                            this.clientScheduler.scheduleAtFixedRate(() -> sendLiveProbeTick(boundUdp, session), 2, 2, TimeUnit.SECONDS);
                        }

                        // Client UDP receive loop
                        Thread recvThread = new Thread(() -> {
                            byte[] b = new byte[2048];
                            while (clientActive && !boundUdp.isClosed()) {
                                try {
                                    DatagramPacket p = new DatagramPacket(b, b.length);
                                    boundUdp.receive(p);
                                    int len = p.getLength();
                                    if (len < HEADER_SIZE || b[0] != MAGIC_0 || b[1] != MAGIC_1) continue;
                                    ByteBuffer bb = ByteBuffer.wrap(b, 0, len);
                                    bb.position(2);
                                    byte type = bb.get();
                                    byte flags = bb.get();
                                    int sid = bb.getInt();
                                    int seq = bb.getInt();
                                    int pLen = bb.getShort() & 0xFFFF;
                                    if (sid != sessionId || !session.acceptsRemoteAddress((InetSocketAddress) p.getSocketAddress())) {
                                        continue;
                                    }
                                    if (type == TYPE_PROBE_ACK && pLen == 12 && len >= HEADER_SIZE + 12) {
                                        onLiveProbeAckReceived(ByteBuffer.wrap(b, HEADER_SIZE, 12));
                                    } else {
                                        handleSessionPacket(session, type, flags, seq, b, HEADER_SIZE, Math.min(pLen, len - HEADER_SIZE));
                                    }
                                } catch (IOException e) {
                                    break;
                                }
                            }
                        }, "PeerPulsar-UDP-Client-Recv");
                        recvThread.setDaemon(true);
                        recvThread.start();

                        // Accept local Minecraft client connection
                        Thread acceptThread = new Thread(() -> {
                            try {
                                boundLocalServer.setSoTimeout(15_000);
                                Socket mcSock = boundLocalServer.accept();
                                try { boundLocalServer.close(); } catch (Exception ignored) {}
                                mcSock.setTcpNoDelay(true);
                                session.attachTcpSocket(mcSock);
                            } catch (SocketTimeoutException e) {
                                if (clientActive && clientLocalServer == boundLocalServer) {
                                    PeerPulsar.LOGGER.warn("[P2P-UDP] Local proxy accept timed out — cleaning up client proxy.");
                                    stopClientProxy();
                                }
                            } catch (IOException e) {
                                if (clientActive && clientLocalServer == boundLocalServer) {
                                    PeerPulsar.LOGGER.warn("[P2P-UDP] Local proxy accept error ({}) — cleaning up client proxy.", e.getMessage());
                                    stopClientProxy();
                                }
                            }
                        }, "PeerPulsar-UDP-Client-Accept");
                        acceptThread.setDaemon(true);
                        acceptThread.start();
                    }
                }

                PeerPulsar.LOGGER.info("[P2P-UDP] Connected! Engine={} (probe RTT={}ms, jitter={}ms, loss={}%), local 127.0.0.1:{}",
                        finalEngine.displayName(),
                        Math.round(probe.avgRttMs()),
                        Math.round(probe.jitterMs()),
                        String.format("%.1f", probe.lossPct()),
                        localProxyPort);
                return new ClientProxyResult(true, localProxyPort, null, finalEngine, probe);
            } catch (Exception e) {
                String errMsg = formatError(e);
                PeerPulsar.LOGGER.error("[P2P-UDP] Client hole punch error: {}", errMsg);
                if (!handedOff) {
                    if (localServer != null) try { localServer.close(); } catch (Exception ignored) {}
                    if (quicConn != null) try { quicConn.close(); } catch (Exception ignored) {}
                    if (quicDemux != null) quicDemux.close();
                    if (udp != null) try { udp.close(); } catch (Exception ignored) {}
                }
                stopClientProxyIfGeneration(gen);
                return new ClientProxyResult(false, -1, errMsg);
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    private TransportSwitchAdvisor.ProbeResult runInitialProbe(
            DatagramSocket udp,
            InetSocketAddress hostAddr,
            int sessionId,
            long gen
    ) {
        final int count = 4;
        long[] sentAtMs = new long[count];
        long[] rtts = new long[count];
        int received = 0;
        try {
            udp.setSoTimeout(30);
            byte[] rxBuf = new byte[256];
            for (int i = 0; i < count && clientGeneration.get() == gen; i++) {
                long now = System.currentTimeMillis();
                sentAtMs[i] = now;
                rtts[i] = -1L;
                byte[] pl = ByteBuffer.allocate(12).putInt(i + 1).putLong(now).array();
                sendPacket(udp, hostAddr, TYPE_PROBE, (byte) 0, sessionId, i + 1, pl, pl.length);

                long stepEnd = System.currentTimeMillis() + 30L;
                while (System.currentTimeMillis() < stepEnd) {
                    try {
                        DatagramPacket rx = new DatagramPacket(rxBuf, rxBuf.length);
                        udp.receive(rx);
                        if (rx.getLength() >= HEADER_SIZE + 12 && rxBuf[0] == MAGIC_0 && rxBuf[1] == MAGIC_1
                                && rxBuf[2] == TYPE_PROBE_ACK) {
                            ByteBuffer pb = ByteBuffer.wrap(rxBuf, HEADER_SIZE, 12);
                            int pid = pb.getInt() - 1;
                            long echoTs = pb.getLong();
                            if (pid >= 0 && pid < count && rtts[pid] < 0) {
                                rtts[pid] = Math.max(1L, System.currentTimeMillis() - echoTs);
                                received++;
                            }
                        }
                    } catch (IOException ignored) {}
                }
            }
            // Wait up to 300ms for delayed probe responses
            long drainDeadline = System.currentTimeMillis() + 300L;
            while (received < count && System.currentTimeMillis() < drainDeadline && clientGeneration.get() == gen) {
                try {
                    udp.setSoTimeout((int) Math.max(10L, drainDeadline - System.currentTimeMillis()));
                    DatagramPacket rx = new DatagramPacket(rxBuf, rxBuf.length);
                    udp.receive(rx);
                    if (rx.getLength() >= HEADER_SIZE + 12 && rxBuf[0] == MAGIC_0 && rxBuf[1] == MAGIC_1
                            && rxBuf[2] == TYPE_PROBE_ACK) {
                        ByteBuffer pb = ByteBuffer.wrap(rxBuf, HEADER_SIZE, 12);
                        int pid = pb.getInt() - 1;
                        long echoTs = pb.getLong();
                        if (pid >= 0 && pid < count && rtts[pid] < 0) {
                            rtts[pid] = Math.max(1L, System.currentTimeMillis() - echoTs);
                            received++;
                        }
                    }
                } catch (IOException ignored) {
                    break;
                }
            }
        } catch (Exception ignored) {}

        if (received == 0) {
            return new TransportSwitchAdvisor.ProbeResult(count, 0, 0.0, 0.0, 100.0);
        }
        double sum = 0.0;
        double jitterSum = 0.0;
        long prev = -1L;
        int jitterCount = 0;
        for (long r : rtts) {
            if (r >= 0) {
                sum += r;
                if (prev >= 0) {
                    jitterSum += Math.abs(r - prev);
                    jitterCount++;
                }
                prev = r;
            }
        }
        double avgRtt = sum / received;
        double jitter = jitterCount > 0 ? (jitterSum / jitterCount) : 0.0;
        double lossPct = ((count - received) * 100.0) / count;
        return new TransportSwitchAdvisor.ProbeResult(count, received, avgRtt, jitter, lossPct);
    }

    private UdpTransportMode negotiateEngine(
            DatagramSocket udp,
            InetSocketAddress hostAddr,
            int sessionId,
            UdpTransportMode desired,
            long gen
    ) {
        try {
            udp.setSoTimeout(200);
            byte[] rxBuf = new byte[256];
            for (int attempt = 0; attempt < 5 && clientGeneration.get() == gen; attempt++) {
                sendPacket(udp, hostAddr, TYPE_SELECT, desired.wireId(), sessionId, 0, null, 0);
                long deadline = System.currentTimeMillis() + 200L;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket rx = new DatagramPacket(rxBuf, rxBuf.length);
                        udp.receive(rx);
                        if (rx.getLength() >= HEADER_SIZE && rxBuf[0] == MAGIC_0 && rxBuf[1] == MAGIC_1
                                && rxBuf[2] == TYPE_SELECT_ACK) {
                            return UdpTransportMode.fromWireId(rxBuf[3]);
                        }
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void sendLiveProbeTick(DatagramSocket udp, PeerSession session) {
        if (!clientActive || udp.isClosed() || session.closed.get()) return;
        try {
            long now = System.currentTimeMillis();
            // Evaluate the previous 5-probe window BEFORE sending the next probe so probe #5 had >= 2s to arrive
            if (liveProbeWindowSent.get() >= 5) {
                int sent = liveProbeWindowSent.getAndSet(0);
                int recv = liveProbeWindowRecv.getAndSet(0);
                double probeLoss = sent > 0 ? Math.max(0.0, ((sent - recv) * 100.0) / sent) : 0.0;
                double dataRetransPct = session.consumeWindowRetransmitPct();
                this.clientLossPct = Math.max(probeLoss, dataRetransPct);
                maybeEvaluateSwitchAdvisor(now);
            }

            int pid = probeSeqGen.getAndIncrement();
            // Evict timed-out probes (> 3s old)
            pendingClientProbes.entrySet().removeIf(e -> now - e.getValue() > 3000L);
            pendingClientProbes.put(pid, now);
            liveProbeWindowSent.incrementAndGet();

            byte[] payload = ByteBuffer.allocate(12).putInt(pid).putLong(now).array();
            sendPacket(udp, session.remoteAddr, TYPE_PROBE, (byte) 0, session.sessionId, pid, payload, payload.length);
        } catch (Exception ignored) {}
    }

    private void onLiveProbeAckReceived(ByteBuffer pb) {
        int pid = pb.getInt();
        long sentTs = pb.getLong();
        if (pendingClientProbes.remove(pid) != null) {
            liveProbeWindowRecv.incrementAndGet();
        }
        long now = System.currentTimeMillis();
        double sampleRtt = Math.max(1.0, now - sentTs);
        double prev = this.clientRttMs;
        if (prev < 0) {
            this.clientRttMs = sampleRtt;
            this.clientJitterMs = 0.0;
        } else {
            double diff = Math.abs(sampleRtt - prev);
            this.clientJitterMs = this.clientJitterMs * 0.75 + diff * 0.25;
            this.clientRttMs = prev * 0.7 + sampleRtt * 0.3;
        }
        maybeEvaluateSwitchAdvisor(now);
    }

    private void pollQuicTelemetry(QuicClientConnection conn) {
        if (!clientActive || conn == null || !conn.isConnected()) return;
        try {
            Statistics stats = conn.getStats();
            if (stats == null) return;
            long now = System.currentTimeMillis();
            if (stats.smoothedRtt() > 0) {
                this.clientRttMs = stats.smoothedRtt();
                this.clientJitterMs = Math.max(0.0, stats.rttVar());
            }
            long sent = stats.packetsSent();
            long lost = stats.lostPackets();
            long dSent = Math.max(0L, sent - lastQuicPacketsSent);
            long dLost = Math.max(0L, lost - lastQuicPacketsLost);
            if (dSent >= 5) {
                lastQuicPacketsSent = sent;
                lastQuicPacketsLost = lost;
                double instLoss = Math.min(100.0, (dLost * 100.0) / dSent);
                this.clientLossPct = this.clientLossPct * 0.5 + instLoss * 0.5;
            }
            maybeEvaluateSwitchAdvisor(now);
        } catch (Exception ignored) {}
    }

    private void maybeEvaluateSwitchAdvisor(long nowMs) {
        if (!clientActive || clientForceManualSelection) return;
        if (!PeerPulsarConfig.get().isAutoTransportRecommendations()) return;
        if (this.clientRttMs < 0) return;

        Optional<TransportSwitchAdvisor.SwitchRecommendation> rec = switchAdvisor.evaluateSample(
                nowMs,
                this.clientActiveEngine,
                this.clientHostSupportsQuic,
                this.clientRttMs,
                this.clientJitterMs,
                this.clientLossPct
        );
        if (rec.isPresent()) {
            Consumer<TransportSwitchAdvisor.SwitchRecommendation> listener = this.switchRecommendationListener;
            if (listener != null) {
                listener.accept(rec.get());
            }
        }
    }

    private static void bridgeTcpAndQuicStream(Socket tcp, QuicStream stream, Runnable onFinish) {
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable closeOnce = () -> {
            if (finished.compareAndSet(false, true)) {
                try { tcp.close(); } catch (Exception ignored) {}
                try { stream.resetStream(0); } catch (Exception ignored) {}
                if (onFinish != null) onFinish.run();
            }
        };

        Thread up = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                InputStream in = tcp.getInputStream();
                OutputStream qOut = stream.getOutputStream();
                int read;
                while ((read = in.read(buf)) != -1) {
                    qOut.write(buf, 0, read);
                    qOut.flush();
                }
                try { qOut.close(); } catch (Exception ignored) {}
            } catch (Exception ignored) {
            } finally {
                closeOnce.run();
            }
        }, "PeerPulsar-QUIC-Up");
        up.setDaemon(true);
        up.start();

        Thread down = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                InputStream qIn = stream.getInputStream();
                OutputStream out = tcp.getOutputStream();
                int read;
                while ((read = qIn.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    out.flush();
                }
                try {
                    if (!tcp.isClosed() && !tcp.isOutputShutdown()) {
                        tcp.shutdownOutput();
                    }
                } catch (Exception ignored) {}
            } catch (Exception ignored) {
            } finally {
                closeOnce.run();
            }
        }, "PeerPulsar-QUIC-Down");
        down.setDaemon(true);
        down.start();
    }

    private void stopClientProxyIfGeneration(long expectedGen) {
        synchronized (clientLock) {
            if (clientGeneration.get() != expectedGen) {
                return;
            }
            stopClientProxy();
        }
    }

    public void stopClientProxyIfPort(int expectedLocalPort) {
        synchronized (clientLock) {
            if (clientLocalServer == null || clientLocalServer.getLocalPort() != expectedLocalPort) {
                return;
            }
            stopClientProxy();
        }
    }

    public void stopClientProxy() {
        clientGeneration.incrementAndGet();
        synchronized (clientLock) {
            this.clientActive = false;
            this.clientRttMs = -1.0;
            this.clientJitterMs = 0.0;
            this.clientLossPct = 0.0;
            this.pendingClientProbes.clear();
            this.switchAdvisor.reset();

            if (clientSignalConn != null) {
                try { clientSignalConn.disconnect(); } catch (Exception ignored) {}
                clientSignalConn = null;
            }
            if (clientScheduler != null) {
                clientScheduler.shutdownNow();
                clientScheduler = null;
            }
            if (clientSession != null) {
                clientSession.close();
                clientSession = null;
            }
            for (AutoCloseable c : clientQuicResources) {
                try { c.close(); } catch (Exception ignored) {}
            }
            clientQuicResources.clear();
            if (clientQuicConn != null) {
                try { clientQuicConn.close(); } catch (Exception ignored) {}
                clientQuicConn = null;
            }
            if (clientQuicDemux != null) {
                try { clientQuicDemux.close(); } catch (Exception ignored) {}
                clientQuicDemux = null;
            }
            if (clientLocalServer != null) {
                try { clientLocalServer.close(); } catch (Exception ignored) {}
                clientLocalServer = null;
            }
            if (clientUdpSocket != null) {
                try { clientUdpSocket.close(); } catch (Exception ignored) {}
                clientUdpSocket = null;
            }
        }
    }

    // ==================== RELIABLE UDP / KCP STREAM ENGINES ====================

    private static void handleSessionPacket(PeerSession session, byte type, byte flags, int seq, byte[] buf, int offset, int len) {
        session.touchActivity();
        if (type == TYPE_DATA) {
            byte[] copy = new byte[len];
            System.arraycopy(buf, offset, copy, 0, len);
            session.onDataReceived(seq, copy);
        } else if (type == TYPE_ACK) {
            long sackMask = 0L;
            boolean hasSack = (flags & FLAG_SACK) != 0 && len >= 8;
            if (hasSack) {
                sackMask = ByteBuffer.wrap(buf, offset, 8).getLong();
            }
            session.onAckReceived(seq, hasSack, sackMask);
        } else if (type == TYPE_FIN) {
            session.onFinReceived(seq);
        }
    }

    /**
     * Computes a 64-bit Selective ACK bitmask relative to {@code expectedRecvSeq},
     * where bit {@code i} (0..63) is 1 iff sequence {@code expectedRecvSeq + 1 + i} is buffered.
     */
    public static long computeSackMask(int expectedRecvSeq, Set<Integer> bufferedSeqs) {
        long mask = 0L;
        for (int i = 0; i < 64; i++) {
            if (bufferedSeqs.contains(expectedRecvSeq + 1 + i)) {
                mask |= (1L << i);
            }
        }
        return mask;
    }

    /**
     * Package-private test hook exercising real UDP datagrams through {@link PeerSession}
     * (RUDP or KCP with SACK/fast-resend recovery).
     */
    static byte[] loopbackSessionTransferForTest(UdpTransportMode engine, byte[] input, boolean dropSeqOneOnce) throws Exception {
        try (DatagramSocket senderUdp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket receiverUdp = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             ServerSocket recvTcpListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket sendTcpListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int sessionId = 424242;
            InetSocketAddress senderAddr = (InetSocketAddress) senderUdp.getLocalSocketAddress();
            InetSocketAddress receiverAddr = (InetSocketAddress) receiverUdp.getLocalSocketAddress();

            PeerSession receiverSession = new PeerSession(sessionId, receiverUdp, senderAddr, engine, null);
            PeerSession senderSession = new PeerSession(sessionId, senderUdp, receiverAddr, engine, null);

            CompletableFuture<byte[]> receivedBytes = new CompletableFuture<>();
            Thread recvTcpAccept = new Thread(() -> {
                try (Socket s = recvTcpListener.accept()) {
                    receivedBytes.complete(s.getInputStream().readAllBytes());
                } catch (Exception e) {
                    receivedBytes.completeExceptionally(e);
                }
            }, "Test-RecvTcp");
            recvTcpAccept.setDaemon(true);
            recvTcpAccept.start();

            Socket recvBridgeSock = new Socket("127.0.0.1", recvTcpListener.getLocalPort());
            receiverSession.attachTcpSocket(recvBridgeSock);

            AtomicBoolean droppedOnce = new AtomicBoolean(!dropSeqOneOnce);
            Thread recvLoop = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (!receiverSession.closed.get() && !receiverUdp.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        receiverUdp.receive(p);
                        int len = p.getLength();
                        if (len < HEADER_SIZE || buf[0] != MAGIC_0 || buf[1] != MAGIC_1) continue;
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                        bb.position(2);
                        byte type = bb.get();
                        byte flags = bb.get();
                        bb.getInt();
                        int seq = bb.getInt();
                        int pLen = bb.getShort() & 0xFFFF;
                        if (type == TYPE_DATA && seq == 1 && droppedOnce.compareAndSet(false, true)) {
                            continue;
                        }
                        handleSessionPacket(receiverSession, type, flags, seq, buf, HEADER_SIZE, Math.min(pLen, len - HEADER_SIZE));
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "Test-UdpRecv");
            recvLoop.setDaemon(true);
            recvLoop.start();

            Thread sendRecvLoop = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (!senderSession.closed.get() && !senderUdp.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        senderUdp.receive(p);
                        int len = p.getLength();
                        if (len < HEADER_SIZE || buf[0] != MAGIC_0 || buf[1] != MAGIC_1) continue;
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                        bb.position(2);
                        byte type = bb.get();
                        byte flags = bb.get();
                        bb.getInt();
                        int seq = bb.getInt();
                        int pLen = bb.getShort() & 0xFFFF;
                        handleSessionPacket(senderSession, type, flags, seq, buf, HEADER_SIZE, Math.min(pLen, len - HEADER_SIZE));
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "Test-UdpSendAck");
            sendRecvLoop.setDaemon(true);
            sendRecvLoop.start();

            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            timer.scheduleAtFixedRate(() -> {
                senderSession.tickRetransmitAndTimeout();
                receiverSession.tickRetransmitAndTimeout();
            }, 10, 10, TimeUnit.MILLISECONDS);

            try {
                Thread writer = new Thread(() -> {
                    try (Socket s = new Socket("127.0.0.1", sendTcpListener.getLocalPort())) {
                        s.getOutputStream().write(input);
                        s.getOutputStream().flush();
                    } catch (IOException ignored) {}
                }, "Test-Writer");
                writer.setDaemon(true);
                writer.start();

                Socket sendBridgeSock = sendTcpListener.accept();
                senderSession.attachTcpSocket(sendBridgeSock);

                return receivedBytes.get(8, TimeUnit.SECONDS);
            } finally {
                timer.shutdownNow();
                senderSession.close();
                receiverSession.close();
            }
        }
    }

    private static final class PeerSession {
        final int sessionId;
        final DatagramSocket udp;
        volatile InetSocketAddress remoteAddr;
        final Runnable onClose;
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile long lastActivityMs = System.currentTimeMillis();
        volatile UdpTransportMode engine;
        volatile long lastTickProcessedMs = 0L;

        // Adaptive RTO estimators
        volatile double srttMs = 100.0;
        volatile double rttVarMs = 30.0;

        // Loss telemetry window
        final AtomicInteger packetsSentWindow = new AtomicInteger(0);
        final AtomicInteger packetsRetransWindow = new AtomicInteger(0);

        volatile Socket tcpSocket;
        final AtomicInteger nextSendSeq = new AtomicInteger(0);
        final Map<Integer, SentChunk> unacked = new ConcurrentHashMap<>();

        final Object recvLock = new Object();
        int expectedRecvSeq = 0;
        final TreeMap<Integer, byte[]> reorderBuffer = new TreeMap<>();
        final BlockingQueue<byte[]> tcpWriteQueue = new LinkedBlockingQueue<>(512);

        volatile int finSeq = -1;
        volatile boolean finReceived = false;
        volatile boolean finDrained = false;
        volatile long finReceivedAtMs = 0L;

        PeerSession(int sessionId, DatagramSocket udp, InetSocketAddress remoteAddr, UdpTransportMode engine, Runnable onClose) {
            this.sessionId = sessionId;
            this.udp = udp;
            this.remoteAddr = remoteAddr;
            this.engine = (engine == UdpTransportMode.KCP) ? UdpTransportMode.KCP : UdpTransportMode.RUDP;
            this.onClose = onClose;
        }

        void setEngine(UdpTransportMode next) {
            if (next == UdpTransportMode.KCP || next == UdpTransportMode.RUDP) {
                this.engine = next;
            }
        }

        private boolean isKcp() {
            return this.engine == UdpTransportMode.KCP;
        }

        private int sendWindow() {
            return isKcp() ? 256 : 96;
        }

        private long minRtoMs() {
            return isKcp() ? 30L : 80L;
        }

        private long baseRtoMs() {
            long est = Math.round(srttMs + Math.max(15.0, 4.0 * rttVarMs));
            long floor = isKcp() ? 45L : 100L;
            long ceil = isKcp() ? 650L : 1200L;
            return Math.max(floor, Math.min(ceil, est));
        }

        double consumeWindowRetransmitPct() {
            int sent = packetsSentWindow.getAndSet(0);
            int retrans = packetsRetransWindow.getAndSet(0);
            if (sent <= 0) return 0.0;
            return Math.min(100.0, (retrans * 100.0) / sent);
        }

        void touchActivity() {
            this.lastActivityMs = System.currentTimeMillis();
        }

        boolean acceptsRemoteAddress(InetSocketAddress addr) {
            InetSocketAddress current = this.remoteAddr;
            if (current == null || addr == null) return false;
            if (current.getAddress().equals(addr.getAddress())) {
                if (current.getPort() != addr.getPort()) {
                    this.remoteAddr = addr;
                }
                return true;
            }
            return false;
        }

        void attachTcpSocket(Socket tcp) {
            attachTcpSocket(tcp, false);
        }

        void attachTcpSocket(Socket tcp, boolean trackHostPeerIp) {
            this.tcpSocket = tcp;
            if (closed.get()) {
                try { tcp.close(); } catch (Exception ignored) {}
                return;
            }
            if (trackHostPeerIp) {
                int lp = tcp.getLocalPort();
                InetSocketAddress ra = this.remoteAddr;
                if (lp > 0 && ra != null && ra.getAddress() != null) {
                    UdpHolePunchService.getInstance().hostLocalPortToPeerIp.put(lp, ra.getAddress().getHostAddress());
                }
                if (closed.get()) {
                    if (lp > 0) UdpHolePunchService.getInstance().hostLocalPortToPeerIp.remove(lp);
                    try { tcp.close(); } catch (Exception ignored) {}
                    return;
                }
            }
            drainOrderedToQueue();

            Thread tcpWriter = new Thread(() -> {
                try {
                    OutputStream out = tcp.getOutputStream();
                    while (!closed.get() && !tcp.isClosed()) {
                        byte[] chunk = tcpWriteQueue.poll(50, TimeUnit.MILLISECONDS);
                        if (chunk != null) {
                            out.write(chunk);
                            while ((chunk = tcpWriteQueue.poll()) != null) {
                                out.write(chunk);
                            }
                            out.flush();
                            drainOrderedToQueue();
                        }
                        if (finDrained && tcpWriteQueue.isEmpty()) {
                            drainOrderedToQueue();
                            if (tcpWriteQueue.isEmpty()) {
                                out.flush();
                                try {
                                    if (!tcp.isClosed() && !tcp.isOutputShutdown()) {
                                        tcp.shutdownOutput();
                                    }
                                } catch (IOException ignored) {}
                                long graceDeadline = System.currentTimeMillis() + 250L;
                                while (!closed.get() && !tcp.isClosed() && System.currentTimeMillis() < graceDeadline) {
                                    Thread.sleep(15);
                                }
                                close();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    close();
                }
            }, "PeerPulsar-" + engine.displayName() + "-TcpWrite-" + sessionId);
            tcpWriter.setDaemon(true);
            tcpWriter.start();

            Thread tcpReader = new Thread(() -> {
                byte[] buf = new byte[MAX_PAYLOAD];
                boolean hitEof = false;
                try {
                    InputStream in = tcp.getInputStream();
                    int read;
                    while (!closed.get() && (read = in.read(buf)) != -1) {
                        int win = sendWindow();
                        while (!closed.get() && unacked.size() >= win) {
                            Thread.sleep(isKcp() ? 2 : 5);
                        }
                        if (closed.get()) break;
                        int seq = nextSendSeq.getAndIncrement();
                        byte[] payload = new byte[read];
                        System.arraycopy(buf, 0, payload, 0, read);
                        long now = System.currentTimeMillis();
                        unacked.put(seq, new SentChunk(payload, now));
                        packetsSentWindow.incrementAndGet();
                        sendPacket(udp, remoteAddr, TYPE_DATA, (byte) 0, sessionId, seq, payload, read);
                    }
                    if (!closed.get()) {
                        hitEof = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    int finSeqToSend = nextSendSeq.get();
                    if (hitEof && !closed.get()) {
                        long drainDeadline = System.currentTimeMillis() + 1500L;
                        while (!closed.get() && !unacked.isEmpty() && System.currentTimeMillis() < drainDeadline) {
                            try {
                                Thread.sleep(15);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    for (int i = 0; i < 3 && !udp.isClosed(); i++) {
                        try {
                            sendPacket(udp, remoteAddr, TYPE_FIN, (byte) 0, sessionId, finSeqToSend, null, 0);
                            if (i < 2) {
                                Thread.sleep(25);
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (IOException ignored) {
                            break;
                        }
                    }
                    close();
                }
            }, "PeerPulsar-" + engine.displayName() + "-Send-" + sessionId);
            tcpReader.setDaemon(true);
            tcpReader.start();
        }

        void onDataReceived(int seq, byte[] data) {
            int ackSeq;
            long sackMask = 0L;
            synchronized (recvLock) {
                int delta = seq - expectedRecvSeq;
                if (delta >= 0 && delta < MAX_REORDER_PACKETS
                        && reorderBuffer.size() < MAX_REORDER_PACKETS) {
                    reorderBuffer.putIfAbsent(seq, data);
                }
                drainOrderedToQueueLocked();
                ackSeq = expectedRecvSeq;
                if (isKcp() && !reorderBuffer.isEmpty()) {
                    sackMask = computeSackMask(ackSeq, reorderBuffer.keySet());
                }
            }
            sendAckPacket(ackSeq, sackMask);
        }

        void onFinReceived(int seq) {
            synchronized (recvLock) {
                if (closed.get()) return;
                if (!finReceived) {
                    this.finSeq = seq;
                    this.finReceived = true;
                    this.finReceivedAtMs = System.currentTimeMillis();
                } else if (seq - this.finSeq > 0) {
                    this.finSeq = seq;
                }
                drainOrderedToQueueLocked();
                checkFinDrainedLocked();
            }
        }

        private void drainOrderedToQueue() {
            int ackToSend = -1;
            long sackMask = 0L;
            synchronized (recvLock) {
                int before = expectedRecvSeq;
                drainOrderedToQueueLocked();
                if (expectedRecvSeq - before > 0) {
                    ackToSend = expectedRecvSeq;
                    if (isKcp() && !reorderBuffer.isEmpty()) {
                        sackMask = computeSackMask(ackToSend, reorderBuffer.keySet());
                    }
                }
            }
            if (ackToSend != -1 && !closed.get()) {
                sendAckPacket(ackToSend, sackMask);
            }
        }

        private void sendAckPacket(int ackSeq, long sackMask) {
            try {
                if (isKcp() && sackMask != 0L) {
                    byte[] payload = ByteBuffer.allocate(8).putLong(sackMask).array();
                    sendPacket(udp, remoteAddr, TYPE_ACK, FLAG_SACK, sessionId, ackSeq, payload, 8);
                } else {
                    sendPacket(udp, remoteAddr, TYPE_ACK, (byte) 0, sessionId, ackSeq, null, 0);
                }
            } catch (Exception ignored) {}
        }

        private void drainOrderedToQueueLocked() {
            while (reorderBuffer.containsKey(expectedRecvSeq)) {
                byte[] chunk = reorderBuffer.get(expectedRecvSeq);
                if (!tcpWriteQueue.offer(chunk)) {
                    break;
                }
                reorderBuffer.remove(expectedRecvSeq);
                expectedRecvSeq++;
            }
            checkFinDrainedLocked();
        }

        private void checkFinDrainedLocked() {
            if (finReceived && !finDrained && (finSeq == 0 || (expectedRecvSeq - finSeq) >= 0)) {
                finDrained = true;
            }
        }

        void onAckReceived(int nextExpected, boolean hasSack, long sackMask) {
            long now = System.currentTimeMillis();
            int highestAcked = nextExpected - 1;

            Iterator<Map.Entry<Integer, SentChunk>> it = unacked.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, SentChunk> entry = it.next();
                int seq = entry.getKey();
                if ((seq - nextExpected) < 0) {
                    SentChunk sc = entry.getValue();
                    if (sc.retries.get() == 0) {
                        updateSrtt(now - sc.firstSentMs);
                    }
                    it.remove();
                }
            }

            if (isKcp() && hasSack && sackMask != 0L) {
                for (int bit = 0; bit < 64; bit++) {
                    if ((sackMask & (1L << bit)) != 0L) {
                        int sackedSeq = nextExpected + 1 + bit;
                        if ((sackedSeq - highestAcked) > 0) {
                            highestAcked = sackedSeq;
                        }
                        SentChunk sc = unacked.remove(sackedSeq);
                        if (sc != null && sc.retries.get() == 0) {
                            updateSrtt(now - sc.firstSentMs);
                        }
                    }
                }
                // Fast-resend skipped packets in KCP after >= 2 out-of-order SACK observations
                long guardMs = minRtoMs();
                for (Map.Entry<Integer, SentChunk> entry : unacked.entrySet()) {
                    if ((entry.getKey() - highestAcked) < 0) {
                        SentChunk sc = entry.getValue();
                        if (sc.fastAckCount.incrementAndGet() >= 2 && (now - sc.lastSentMs) >= guardMs) {
                            sc.fastAckCount.set(0);
                            sc.lastSentMs = now;
                            sc.retries.incrementAndGet();
                            packetsSentWindow.incrementAndGet();
                            packetsRetransWindow.incrementAndGet();
                            try {
                                sendPacket(udp, remoteAddr, TYPE_DATA, (byte) 0, sessionId, entry.getKey(), sc.data, sc.data.length);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }

        private void updateSrtt(long sampleRttMs) {
            if (sampleRttMs < 1 || sampleRttMs > 10_000) return;
            double diff = Math.abs(sampleRttMs - srttMs);
            rttVarMs = rttVarMs * 0.75 + diff * 0.25;
            srttMs = srttMs * 0.875 + sampleRttMs * 0.125;
        }

        void tickRetransmitAndTimeout() {
            if (closed.get()) return;
            long now = System.currentTimeMillis();
            if (!isKcp() && (now - lastTickProcessedMs) < 28L) {
                return;
            }
            lastTickProcessedMs = now;

            if (now - lastActivityMs > SESSION_IDLE_TIMEOUT_MS) {
                PeerPulsar.LOGGER.info("[P2P-UDP] Session {} timed out after inactivity.", sessionId);
                close();
                return;
            }
            if (finReceived) {
                if (!finDrained && now - finReceivedAtMs >= 1500L) {
                    synchronized (recvLock) {
                        drainOrderedToQueueLocked();
                        finDrained = true;
                    }
                    if (tcpSocket == null) {
                        close();
                        return;
                    }
                } else if (finDrained && tcpSocket == null && now - finReceivedAtMs >= 1500L) {
                    close();
                    return;
                } else if (finDrained && now - finReceivedAtMs >= 3000L) {
                    close();
                    return;
                }
            }
            if (unacked.isEmpty()) return;

            long baseRto = baseRtoMs();
            long maxRto = isKcp() ? 800L : 1500L;
            for (Map.Entry<Integer, SentChunk> entry : unacked.entrySet()) {
                SentChunk chunk = entry.getValue();
                int rCount = chunk.retries.get();
                long rto;
                if (isKcp()) {
                    // Milder 1.5x backoff for KCP
                    double mult = Math.pow(1.5, Math.min(rCount, 4));
                    rto = Math.min(maxRto, Math.round(baseRto * mult));
                } else {
                    rto = Math.min(maxRto, baseRto << Math.min(rCount, 4));
                }
                if (now - chunk.lastSentMs >= rto) {
                    if (rCount >= MAX_RETRANSMITS) {
                        PeerPulsar.LOGGER.warn("[P2P-UDP] Session {} exceeded max retransmits — closing.", sessionId);
                        close();
                        return;
                    }
                    chunk.retries.incrementAndGet();
                    chunk.lastSentMs = now;
                    chunk.fastAckCount.set(0);
                    packetsSentWindow.incrementAndGet();
                    packetsRetransWindow.incrementAndGet();
                    try {
                        sendPacket(udp, remoteAddr, TYPE_DATA, (byte) 0, sessionId, entry.getKey(), chunk.data, chunk.data.length);
                    } catch (Exception ignored) {}
                }
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                Socket s = this.tcpSocket;
                if (s != null) {
                    int lp = s.getLocalPort();
                    if (lp > 0) {
                        UdpHolePunchService.getInstance().hostLocalPortToPeerIp.remove(lp);
                    }
                    try { s.close(); } catch (Exception ignored) {}
                }
                unacked.clear();
                synchronized (recvLock) {
                    reorderBuffer.clear();
                }
                tcpWriteQueue.clear();
                if (onClose != null) onClose.run();
            }
        }
    }

    private static final class SentChunk {
        final byte[] data;
        final long firstSentMs;
        volatile long lastSentMs;
        final AtomicInteger retries = new AtomicInteger(0);
        final AtomicInteger fastAckCount = new AtomicInteger(0);

        SentChunk(byte[] data, long nowMs) {
            this.data = data;
            this.firstSentMs = nowMs;
            this.lastSentMs = nowMs;
        }
    }

    private static String formatError(Throwable t) {
        if (t == null) return "Unknown error";
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
    }

    // ==================== STUN & SIGNALING HELPERS ====================

    private static boolean isNumericIpLiteral(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45) return false;
        if (ip.indexOf(':') < 0) {
            String[] parts = ip.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String p : parts) {
                if (p.isEmpty() || p.length() > 3) return false;
                int val = 0;
                for (int i = 0; i < p.length(); i++) {
                    char c = p.charAt(i);
                    if (c < '0' || c > '9') return false;
                    val = val * 10 + (c - '0');
                }
                if (val > 255) return false;
            }
            return true;
        }
        int doubleColon = ip.indexOf("::");
        if (doubleColon >= 0 && ip.indexOf("::", doubleColon + 2) >= 0) return false;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') return false;
        }
        return true;
    }

    private static void sendPacket(
            DatagramSocket udp,
            InetSocketAddress dst,
            byte type,
            byte flags,
            int sessionId,
            int seq,
            byte[] payload,
            int len
    ) throws IOException {
        int payloadLen = (payload != null) ? len : 0;
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + payloadLen);
        bb.put(MAGIC_0);
        bb.put(MAGIC_1);
        bb.put(type);
        bb.put(flags);
        bb.putInt(sessionId);
        bb.putInt(seq);
        bb.putShort((short) payloadLen);
        if (payloadLen > 0) {
            bb.put(payload, 0, payloadLen);
        }
        byte[] bytes = bb.array();
        udp.send(new DatagramPacket(bytes, bytes.length, dst));
    }

    private static InetAddress resolveIpv4(String host) throws IOException {
        return NetworkEndpoints.resolvePinnedPublicAddress(host, true);
    }

    public static InetSocketAddress queryStunOnSocket(DatagramSocket socket, String host, int port) {
        int oldTimeout = 0;
        boolean savedTimeout = false;
        try {
            oldTimeout = socket.getSoTimeout();
            savedTimeout = true;

            int tx0 = ThreadLocalRandom.current().nextInt();
            int tx1 = ThreadLocalRandom.current().nextInt();
            int tx2 = ThreadLocalRandom.current().nextInt();

            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) 0x0001);
            req.putShort((short) 0x0000);
            req.putInt(0x2112A442);
            req.putInt(tx0);
            req.putInt(tx1);
            req.putInt(tx2);
            byte[] tx = req.array();
            socket.send(new DatagramPacket(tx, tx.length, resolveIpv4(host), port));

            byte[] rx = new byte[512];
            long deadline = System.currentTimeMillis() + 2000L;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                socket.setSoTimeout((int) Math.max(1L, remaining));

                DatagramPacket rxPkt = new DatagramPacket(rx, rx.length);
                try {
                    socket.receive(rxPkt);
                } catch (SocketTimeoutException e) {
                    break;
                }

                ByteBuffer resp = ByteBuffer.wrap(rxPkt.getData(), 0, rxPkt.getLength());
                if (resp.remaining() < 20) continue;
                short msgType = resp.getShort();
                int msgLen = resp.getShort() & 0xFFFF;
                int cookie = resp.getInt();
                int rTx0 = resp.getInt();
                int rTx1 = resp.getInt();
                int rTx2 = resp.getInt();
                if (msgType != (short) 0x0101 || cookie != 0x2112A442
                        || rTx0 != tx0 || rTx1 != tx1 || rTx2 != tx2) {
                    continue;
                }

                resp.limit(Math.min(resp.limit(), 20 + msgLen));
                InetSocketAddress fallbackMapped = null;
                while (resp.remaining() >= 4) {
                    short attrType = resp.getShort();
                    int attrLen = resp.getShort() & 0xFFFF;
                    int attrStart = resp.position();
                    if (resp.remaining() < attrLen) break;

                    if (attrType == 0x0020 && attrLen >= 8) {
                        resp.get();
                        byte family = resp.get();
                        int xPort = (resp.getShort() & 0xFFFF) ^ 0x2112;
                        if (family == 0x01) {
                            byte[] ipBytes = new byte[4];
                            resp.get(ipBytes);
                            for (int i = 0; i < 4; i++) {
                                ipBytes[i] = (byte) (ipBytes[i] ^ ((0x2112A442 >> (24 - i * 8)) & 0xFF));
                            }
                            return new InetSocketAddress(InetAddress.getByAddress(ipBytes), xPort);
                        }
                    } else if (attrType == 0x0001 && attrLen >= 8 && fallbackMapped == null) {
                        resp.get();
                        byte family = resp.get();
                        int mappedPort = resp.getShort() & 0xFFFF;
                        if (family == 0x01) {
                            byte[] ipBytes = new byte[4];
                            resp.get(ipBytes);
                            fallbackMapped = new InetSocketAddress(InetAddress.getByAddress(ipBytes), mappedPort);
                        }
                    }
                    int padded = (attrLen + 3) & ~3;
                    resp.position(Math.min(resp.limit(), attrStart + padded));
                }
                if (fallbackMapped != null) {
                    return fallbackMapped;
                }
                return null;
            }
        } catch (Exception ignored) {
        } finally {
            if (savedTimeout && !socket.isClosed()) {
                try { socket.setSoTimeout(oldTimeout); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static void sendStunKeepalive(DatagramSocket socket, InetAddress stunAddr, int port) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(20);
        req.putShort((short) 0x0001);
        req.putShort((short) 0x0000);
        req.putInt(0x2112A442);
        for (int i = 0; i < 3; i++) req.putInt(ThreadLocalRandom.current().nextInt());
        byte[] tx = req.array();
        socket.send(new DatagramPacket(tx, tx.length, stunAddr, port));
    }

    private static void publishSignal(String signalingBase, String topic, String body) throws IOException {
        HttpsURLConnection conn = NetworkEndpoints.openPinnedPublicHttpsConnection(
                signalingBase + "/" + topic, 4000, 4000);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                PeerPulsar.LOGGER.warn("[P2P-UDP] Signaling publish returned HTTP {}", status);
                throw new IOException("Signaling server returned HTTP " + status);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String randomCode(int len) {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CODE_ALPHABET.charAt(rng.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private record HostSignalInfo(InetSocketAddress addr, String quicCertSha256, int protoVersion) {}

    public record TransportTelemetry(
            UdpTransportMode activeEngine,
            boolean hostSupportsQuic,
            double rttMs,
            double jitterMs,
            double lossPct,
            long connectedAtMs
    ) {}

    public record HostStartResult(boolean success, String roomCode, String publicIp, int publicPort, String error, String signalingUrl) {
        public HostStartResult(boolean success, String roomCode, String publicIp, int publicPort, String error) {
            this(success, roomCode, publicIp, publicPort, error, NetworkEndpoints.DEFAULT_SIGNALING_URL);
        }
    }

    public record ClientProxyResult(
            boolean success,
            int localPort,
            String error,
            UdpTransportMode activeEngine,
            TransportSwitchAdvisor.ProbeResult probeResult
    ) {
        public ClientProxyResult(boolean success, int localPort, String error) {
            this(success, localPort, error, UdpTransportMode.RUDP, null);
        }
    }
}
