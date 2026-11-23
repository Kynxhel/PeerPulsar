package com.p2pworld.client.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.p2pworld.FabricP2PMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Essential-style UDP Hole Punching P2P engine with Reliable Ordered UDP (RUDP).
 * Uses IPv4 Dual-STUN for NAT traversal and ntfy.sh HTTPS streaming for zero-config peer signaling,
 * allowing direct peer-to-peer Minecraft play even behind Cone CGNAT.
 */
public class UdpHolePunchService {
    private static final String SIGNAL_BASE = "https://ntfy.sh/";
    private static final String STUN_HOST = "stun.l.google.com";
    private static final int STUN_PORT = 19302;
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final byte MAGIC_0 = 0x50; // 'P'
    private static final byte MAGIC_1 = 0x32; // '2'
    private static final int HEADER_SIZE = 14;
    private static final int MAX_PAYLOAD = 1180;
    private static final int MAX_REORDER_PACKETS = 256;
    private static final int MAX_RETRANSMITS = 15;
    private static final long SESSION_IDLE_TIMEOUT_MS = 20_000L;
    private static final long INITIAL_RTO_MS = 180L;
    private static final long MAX_RTO_MS = 1500L;

    private static final byte TYPE_PUNCH = 1;
    private static final byte TYPE_PUNCH_ACK = 2;
    private static final byte TYPE_DATA = 3;
    private static final byte TYPE_ACK = 4;
    private static final byte TYPE_FIN = 5;
    private static final byte TYPE_KEEPALIVE = 6;

    private static UdpHolePunchService instance;

    // Host state
    private final Object hostLock = new Object();
    private final AtomicLong hostGeneration = new AtomicLong(0);
    private volatile boolean hostActive = false;
    private volatile String hostRoomCode = null;
    private volatile String hostTopic = null;
    private volatile String hostPublicIp = null;
    private volatile int hostPublicPort = -1;
    private volatile DatagramSocket hostUdpSocket = null;
    private volatile HttpURLConnection hostSignalConn = null;
    private volatile ScheduledExecutorService hostScheduler = null;
    private volatile ExecutorService punchExecutor = null;
    private final Map<Integer, PeerSession> hostSessions = new ConcurrentHashMap<>();

    // Client join proxy state
    private final Object clientLock = new Object();
    private final AtomicLong clientGeneration = new AtomicLong(0);
    private volatile boolean clientActive = false;
    private volatile DatagramSocket clientUdpSocket = null;
    private volatile ServerSocket clientLocalServer = null;
    private volatile PeerSession clientSession = null;
    private volatile ScheduledExecutorService clientScheduler = null;

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

    public String getHostRoomCode() {
        return hostRoomCode;
    }

    public String getHostPublicIp() {
        return hostPublicIp;
    }

    public int getHostPublicPort() {
        return hostPublicPort;
    }

    // ==================== HOST SIDE ====================

    public CompletableFuture<HostStartResult> startHostAsync(int localMcPort) {
        return CompletableFuture.supplyAsync(() -> {
            stopHost();
            final long gen = hostGeneration.incrementAndGet();
            DatagramSocket udp = null;
            boolean handedOff = false;
            try {
                final DatagramSocket boundUdp = new DatagramSocket();
                udp = boundUdp;
                boundUdp.setReceiveBufferSize(512 * 1024);
                boundUdp.setSendBufferSize(512 * 1024);

                InetSocketAddress mapped = queryStunOnSocket(boundUdp, STUN_HOST, STUN_PORT);
                if (mapped == null) {
                    boundUdp.close();
                    return new HostStartResult(false, null, null, -1, "STUN mapping failed");
                }

                String rawId = randomCode(12);
                String roomCode = "UDP-" + rawId.substring(0, 4) + "-" + rawId.substring(4, 8) + "-" + rawId.substring(8, 12);
                String topic = "fp2p-" + rawId.toLowerCase();
                String publicIp = mapped.getAddress().getHostAddress();
                int publicPort = mapped.getPort();

                synchronized (hostLock) {
                    if (hostGeneration.get() != gen) {
                        boundUdp.close();
                        return new HostStartResult(false, null, null, -1, "Host startup cancelled");
                    }

                    this.hostUdpSocket = boundUdp;
                    this.hostPublicIp = publicIp;
                    this.hostPublicPort = publicPort;
                    this.hostRoomCode = roomCode;
                    this.hostTopic = topic;
                    this.hostActive = true;
                    handedOff = true;

                    this.punchExecutor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r, "FabricP2P-UDP-PunchOut");
                        t.setDaemon(true);
                        return t;
                    });

                    this.hostScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "FabricP2P-UDP-Host-Timer");
                        t.setDaemon(true);
                        return t;
                    });

                    // Keep CGNAT UDP port mapping alive
                    this.hostScheduler.scheduleAtFixedRate(() -> {
                        if (!hostActive) return;
                        try {
                            sendStunKeepalive(boundUdp, STUN_HOST, STUN_PORT);
                        } catch (Exception ignored) {}
                    }, 12, 12, TimeUnit.SECONDS);

                    // Retransmit & idle session eviction loop
                    this.hostScheduler.scheduleAtFixedRate(() -> {
                        if (!hostActive) return;
                        for (PeerSession session : hostSessions.values()) {
                            session.tickRetransmitAndTimeout();
                        }
                    }, 60, 60, TimeUnit.MILLISECONDS);

                    // UDP receive loop
                    Thread recvThread = new Thread(() -> runHostUdpLoop(gen, boundUdp, localMcPort), "FabricP2P-UDP-Host-Recv");
                    recvThread.setDaemon(true);
                    recvThread.start();

                    // Signaling listener loop (ntfy.sh streaming NDJSON)
                    Thread signalThread = new Thread(() -> runHostSignalLoop(gen, topic, boundUdp), "FabricP2P-UDP-Host-Signal");
                    signalThread.setDaemon(true);
                    signalThread.start();
                }

                FabricP2PMod.LOGGER.info("[P2P-UDP] Host ready! RoomCode={} | STUN={}:{} -> 127.0.0.1:{}",
                        roomCode, publicIp, publicPort, localMcPort);
                return new HostStartResult(true, roomCode, publicIp, publicPort, null);
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[P2P-UDP] Failed to start UDP host: {}", e.getMessage());
                if (!handedOff && udp != null) {
                    try { udp.close(); } catch (Exception ignored) {}
                }
                if (hostGeneration.get() == gen) {
                    stopHost();
                }
                return new HostStartResult(false, null, null, -1, e.getMessage());
            }
        });
    }

    private void runHostSignalLoop(long gen, String topic, DatagramSocket udp) {
        while (hostActive && hostGeneration.get() == gen) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(SIGNAL_BASE + topic + "/json").toURL().openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(0);
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
                            if (!"message".equals(env.has("event") ? env.get("event").getAsString() : "")) continue;
                            if (!env.has("message") || env.get("message").isJsonNull()) continue;
                            String msgStr = env.get("message").getAsString();
                            JsonObject payload = JsonParser.parseString(msgStr).getAsJsonObject();
                            if (!"join".equals(payload.has("role") ? payload.get("role").getAsString() : "")) continue;

                            String peerId = payload.get("id").getAsString();
                            String peerIp = payload.get("ip").getAsString();
                            int peerPort = payload.get("port").getAsInt();
                            int sessionId = payload.get("sid").getAsInt();

                            if (peerPort < 1 || peerPort > 65535 || peerId.length() > 32 || !isNumericIpLiteral(peerIp)) continue;
                            InetAddress parsedIp = InetAddress.ofLiteral(peerIp);
                            if (parsedIp.isLoopbackAddress() || parsedIp.isMulticastAddress() || parsedIp.isAnyLocalAddress()) {
                                continue;
                            }

                            if (!hostActive || hostGeneration.get() != gen) {
                                break;
                            }

                            // Build reply safely with JsonObject
                            JsonObject replyObj = new JsonObject();
                            replyObj.addProperty("role", "host");
                            replyObj.addProperty("to", peerId);
                            replyObj.addProperty("ip", hostPublicIp);
                            replyObj.addProperty("port", hostPublicPort);
                            replyObj.addProperty("sid", sessionId);
                            publishSignal(topic, replyObj.toString());

                            // Actively punch toward the joiner's endpoint using bounded pool
                            InetSocketAddress peerAddr = new InetSocketAddress(parsedIp, peerPort);
                            ExecutorService exec = this.punchExecutor;
                            if (hostActive && hostGeneration.get() == gen && exec != null && !exec.isShutdown()) {
                                exec.submit(() -> {
                                    for (int i = 0; i < 20 && hostActive && hostGeneration.get() == gen && !udp.isClosed(); i++) {
                                        try {
                                            sendPacket(udp, peerAddr, TYPE_PUNCH, sessionId, 0, null, 0);
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
                if (len < HEADER_SIZE || buf[0] != MAGIC_0 || buf[1] != MAGIC_1) continue;

                ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                bb.position(2);
                byte type = bb.get();
                bb.get(); // flags
                int sessionId = bb.getInt();
                int seq = bb.getInt();
                int payloadLen = bb.getShort() & 0xFFFF;
                InetSocketAddress remoteAddr = (InetSocketAddress) pkt.getSocketAddress();

                if (type == TYPE_PUNCH) {
                    sendPacket(udp, remoteAddr, TYPE_PUNCH_ACK, sessionId, 0, null, 0);
                    getOrCreateHostSession(sessionId, udp, remoteAddr, localMcPort);
                } else if (type == TYPE_PUNCH_ACK) {
                    getOrCreateHostSession(sessionId, udp, remoteAddr, localMcPort);
                } else {
                    PeerSession session = hostSessions.get(sessionId);
                    if (session == null && type == TYPE_DATA && seq == 0) {
                        session = getOrCreateHostSession(sessionId, udp, remoteAddr, localMcPort);
                    }
                    if (session != null && session.acceptsRemoteAddress(remoteAddr)) {
                        handleSessionPacket(session, type, seq, buf, HEADER_SIZE, Math.min(payloadLen, len - HEADER_SIZE));
                    }
                }
            } catch (IOException e) {
                if (hostActive && hostGeneration.get() == gen) {
                    FabricP2PMod.LOGGER.debug("[P2P-UDP] Host recv loop closed: {}", e.getMessage());
                }
                break;
            }
        }
    }

    private PeerSession getOrCreateHostSession(int sessionId, DatagramSocket udp, InetSocketAddress remoteAddr, int localMcPort) {
        return hostSessions.computeIfAbsent(sessionId, id -> {
            FabricP2PMod.LOGGER.info("[P2P-UDP] Peer punched through! Session={} Remote={}", id, remoteAddr);
            PeerSession session = new PeerSession(id, udp, remoteAddr, () -> hostSessions.remove(id));
            Thread tcpStarter = new Thread(() -> {
                try {
                    Socket localTcp = new Socket();
                    localTcp.connect(new InetSocketAddress("127.0.0.1", localMcPort), 4000);
                    localTcp.setTcpNoDelay(true);
                    session.attachTcpSocket(localTcp);
                } catch (Exception e) {
                    FabricP2PMod.LOGGER.warn("[P2P-UDP] Failed to connect to local MC server: {}", e.getMessage());
                    session.close();
                }
            }, "FabricP2P-UDP-HostTCP-" + id);
            tcpStarter.setDaemon(true);
            tcpStarter.start();
            return session;
        });
    }

    public void stopHost() {
        hostGeneration.incrementAndGet();
        synchronized (hostLock) {
            this.hostActive = false;
            this.hostRoomCode = null;
            this.hostTopic = null;
            this.hostPublicIp = null;
            this.hostPublicPort = -1;

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

            if (hostUdpSocket != null) {
                try { hostUdpSocket.close(); } catch (Exception ignored) {}
                hostUdpSocket = null;
            }
        }
    }

    // ==================== CLIENT (JOINER) SIDE ====================

    /**
     * Punches a direct UDP P2P link to the host identified by UDP-XXXX-XXXX-XXXX
     * and opens a local TCP proxy on 127.0.0.1:<localPort> for Minecraft's ConnectScreen.
     */
    public CompletableFuture<ClientProxyResult> startClientProxyAsync(String udpRoomCode) {
        return CompletableFuture.supplyAsync(() -> {
            stopClientProxy();
            final long gen = clientGeneration.incrementAndGet();
            DatagramSocket udp = null;
            ServerSocket localServer = null;
            boolean handedOff = false;
            try {
                String clean = udpRoomCode.toUpperCase().replace("UDP-", "").replace("-", "").trim();
                if (clean.length() < 8) {
                    return new ClientProxyResult(false, -1, "Invalid UDP room code");
                }
                String topic = "fp2p-" + clean.toLowerCase();
                String peerId = randomCode(8);
                int sessionId = ThreadLocalRandom.current().nextInt(100_000, Integer.MAX_VALUE);

                final DatagramSocket boundUdp = new DatagramSocket();
                udp = boundUdp;
                boundUdp.setReceiveBufferSize(512 * 1024);
                boundUdp.setSendBufferSize(512 * 1024);

                InetSocketAddress myMapped = queryStunOnSocket(boundUdp, STUN_HOST, STUN_PORT);
                if (myMapped == null) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "STUN discovery failed on client");
                }
                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                // Subscribe to signaling BEFORE publishing join request, synchronizing on stream open
                CompletableFuture<InetSocketAddress> hostAddrFuture = new CompletableFuture<>();
                CountDownLatch subscribedLatch = new CountDownLatch(1);
                HttpURLConnection signalConn = (HttpURLConnection) URI.create(SIGNAL_BASE + topic + "/json").toURL().openConnection();
                signalConn.setConnectTimeout(5000);
                signalConn.setReadTimeout(9000);

                Thread sigReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(signalConn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!hostAddrFuture.isDone() && (line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonObject env = JsonParser.parseString(line).getAsJsonObject();
                                String event = env.has("event") ? env.get("event").getAsString() : "";
                                if ("open".equals(event)) {
                                    subscribedLatch.countDown();
                                    continue;
                                }
                                if (!"message".equals(event) || !env.has("message")) continue;
                                JsonObject payload = JsonParser.parseString(env.get("message").getAsString()).getAsJsonObject();
                                if ("host".equals(payload.has("role") ? payload.get("role").getAsString() : "")
                                        && peerId.equals(payload.has("to") ? payload.get("to").getAsString() : "")) {
                                    String hIp = payload.get("ip").getAsString();
                                    int hPort = payload.get("port").getAsInt();
                                    if (hPort >= 1 && hPort <= 65535 && isNumericIpLiteral(hIp)) {
                                        InetAddress hostIpAddr = InetAddress.ofLiteral(hIp);
                                        if (!hostIpAddr.isLoopbackAddress() && !hostIpAddr.isMulticastAddress() && !hostIpAddr.isAnyLocalAddress()) {
                                            hostAddrFuture.complete(new InetSocketAddress(hostIpAddr, hPort));
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        subscribedLatch.countDown();
                        hostAddrFuture.completeExceptionally(e);
                    }
                }, "FabricP2P-UDP-ClientSignal");
                sigReader.setDaemon(true);
                sigReader.start();

                InetSocketAddress hostAddr;
                try {
                    // Wait for ntfy.sh open event (up to 2.5s) before publishing join request
                    subscribedLatch.await(2500, TimeUnit.MILLISECONDS);
                    JsonObject joinObj = new JsonObject();
                    joinObj.addProperty("role", "join");
                    joinObj.addProperty("id", peerId);
                    joinObj.addProperty("ip", myMapped.getAddress().getHostAddress());
                    joinObj.addProperty("port", myMapped.getPort());
                    joinObj.addProperty("sid", sessionId);
                    publishSignal(topic, joinObj.toString());

                    hostAddr = hostAddrFuture.get(7, TimeUnit.SECONDS);
                } finally {
                    try { signalConn.disconnect(); } catch (Exception ignored) {}
                }

                if (clientGeneration.get() != gen) {
                    boundUdp.close();
                    return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                }

                // Perform UDP Hole Punching handshake with Host
                boundUdp.setSoTimeout(150);
                boolean punched = false;
                byte[] rxBuf = new byte[2048];
                long punchDeadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < punchDeadline && clientGeneration.get() == gen) {
                    sendPacket(boundUdp, hostAddr, TYPE_PUNCH, sessionId, 0, null, 0);
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
                                sendPacket(boundUdp, hostAddr, TYPE_PUNCH_ACK, sessionId, 0, null, 0);
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
                boundUdp.setSoTimeout(0);
                final ServerSocket boundLocalServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                localServer = boundLocalServer;
                int localProxyPort = boundLocalServer.getLocalPort();

                synchronized (clientLock) {
                    if (clientGeneration.get() != gen) {
                        try { boundLocalServer.close(); } catch (Exception ignored) {}
                        try { boundUdp.close(); } catch (Exception ignored) {}
                        return new ClientProxyResult(false, -1, "Client proxy startup cancelled");
                    }

                    this.clientUdpSocket = boundUdp;
                    this.clientLocalServer = boundLocalServer;
                    this.clientActive = true;
                    handedOff = true;

                    PeerSession session = new PeerSession(sessionId, boundUdp, punchedHostAddr, this::stopClientProxy);
                    this.clientSession = session;

                    this.clientScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "FabricP2P-UDP-Client-Timer");
                        t.setDaemon(true);
                        return t;
                    });
                    this.clientScheduler.scheduleAtFixedRate(() -> {
                        if (clientActive && clientSession != null) {
                            clientSession.tickRetransmitAndTimeout();
                        }
                    }, 60, 60, TimeUnit.MILLISECONDS);
                    this.clientScheduler.scheduleAtFixedRate(() -> {
                        if (clientActive && !boundUdp.isClosed()) {
                            try {
                                sendPacket(boundUdp, session.remoteAddr, TYPE_KEEPALIVE, sessionId, 0, null, 0);
                            } catch (Exception ignored) {}
                        }
                    }, 6, 6, TimeUnit.SECONDS);

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
                                bb.get();
                                int sid = bb.getInt();
                                int seq = bb.getInt();
                                int pLen = bb.getShort() & 0xFFFF;
                                if (sid == sessionId && session.acceptsRemoteAddress((InetSocketAddress) p.getSocketAddress())) {
                                    handleSessionPacket(session, type, seq, b, HEADER_SIZE, Math.min(pLen, len - HEADER_SIZE));
                                }
                            } catch (IOException e) {
                                break;
                            }
                        }
                    }, "FabricP2P-UDP-Client-Recv");
                    recvThread.setDaemon(true);
                    recvThread.start();

                    // Accept local Minecraft client connection
                    Thread acceptThread = new Thread(() -> {
                        try {
                            boundLocalServer.setSoTimeout(15_000);
                            Socket mcSock = boundLocalServer.accept();
                            mcSock.setTcpNoDelay(true);
                            session.attachTcpSocket(mcSock);
                        } catch (SocketTimeoutException e) {
                            if (clientActive && clientLocalServer == boundLocalServer) {
                                FabricP2PMod.LOGGER.warn("[P2P-UDP] Local proxy accept timed out — cleaning up client proxy.");
                                stopClientProxy();
                            }
                        } catch (IOException e) {
                            if (clientActive && clientLocalServer == boundLocalServer) {
                                FabricP2PMod.LOGGER.warn("[P2P-UDP] Local proxy accept error ({}) — cleaning up client proxy.", e.getMessage());
                                stopClientProxy();
                            }
                        }
                    }, "FabricP2P-UDP-Client-Accept");
                    acceptThread.setDaemon(true);
                    acceptThread.start();
                }

                FabricP2PMod.LOGGER.info("[P2P-UDP] Direct UDP P2P punched to {}! Local MC proxy on 127.0.0.1:{}",
                        punchedHostAddr, localProxyPort);
                return new ClientProxyResult(true, localProxyPort, null);
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[P2P-UDP] Client hole punch error: {}", e.getMessage());
                if (!handedOff) {
                    if (localServer != null) {
                        try { localServer.close(); } catch (Exception ignored) {}
                    }
                    if (udp != null) {
                        try { udp.close(); } catch (Exception ignored) {}
                    }
                }
                if (clientGeneration.get() == gen) {
                    stopClientProxy();
                }
                return new ClientProxyResult(false, -1, e.getMessage());
            }
        });
    }

    public void stopClientProxy() {
        clientGeneration.incrementAndGet();
        synchronized (clientLock) {
            this.clientActive = false;
            if (clientScheduler != null) {
                clientScheduler.shutdownNow();
                clientScheduler = null;
            }
            if (clientSession != null) {
                clientSession.close();
                clientSession = null;
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

    // ==================== RELIABLE UDP STREAM (RUDP) ====================

    private static void handleSessionPacket(PeerSession session, byte type, int seq, byte[] buf, int offset, int len) {
        session.touchActivity();
        if (type == TYPE_DATA) {
            byte[] copy = new byte[len];
            System.arraycopy(buf, offset, copy, 0, len);
            session.onDataReceived(seq, copy);
        } else if (type == TYPE_ACK) {
            session.onAckReceived(seq);
        } else if (type == TYPE_FIN) {
            session.onFinReceived(seq);
        }
    }

    private static final class PeerSession {
        final int sessionId;
        final DatagramSocket udp;
        volatile InetSocketAddress remoteAddr;
        final Runnable onClose;
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile long lastActivityMs = System.currentTimeMillis();

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

        PeerSession(int sessionId, DatagramSocket udp, InetSocketAddress remoteAddr, Runnable onClose) {
            this.sessionId = sessionId;
            this.udp = udp;
            this.remoteAddr = remoteAddr;
            this.onClose = onClose;
        }

        void touchActivity() {
            this.lastActivityMs = System.currentTimeMillis();
        }

        /**
         * Prevents session hijacking from unrelated remote IPs while allowing NAT port rebinding from the same IP.
         */
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
            if (closed.get()) {
                try { tcp.close(); } catch (Exception ignored) {}
                return;
            }
            this.tcpSocket = tcp;
            drainOrderedToQueue();

            // Dedicated TCP writer thread so slow local TCP writes never stall the shared host UDP recv loop
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
                            // Immediately advance any packets held in reorderBuffer while tcpWriteQueue was full
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
                                // Give local MC socket a brief grace window to read flushed disconnect/kick packets before closing
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
            }, "FabricP2P-RUDP-TcpWrite-" + sessionId);
            tcpWriter.setDaemon(true);
            tcpWriter.start();

            Thread tcpReader = new Thread(() -> {
                byte[] buf = new byte[MAX_PAYLOAD];
                boolean hitEof = false;
                try {
                    InputStream in = tcp.getInputStream();
                    int read;
                    while (!closed.get() && (read = in.read(buf)) != -1) {
                        while (!closed.get() && unacked.size() > 48) {
                            Thread.sleep(5);
                        }
                        if (closed.get()) break;
                        int seq = nextSendSeq.getAndIncrement();
                        byte[] payload = new byte[read];
                        System.arraycopy(buf, 0, payload, 0, read);
                        unacked.put(seq, new SentChunk(payload, System.currentTimeMillis()));
                        sendPacket(udp, remoteAddr, TYPE_DATA, sessionId, seq, payload, read);
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
                            sendPacket(udp, remoteAddr, TYPE_FIN, sessionId, finSeqToSend, null, 0);
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
            }, "FabricP2P-RUDP-Send-" + sessionId);
            tcpReader.setDaemon(true);
            tcpReader.start();
        }

        void onDataReceived(int seq, byte[] data) {
            int ackSeq;
            synchronized (recvLock) {
                if (seq >= expectedRecvSeq && (seq - expectedRecvSeq) < MAX_REORDER_PACKETS
                        && reorderBuffer.size() < MAX_REORDER_PACKETS) {
                    reorderBuffer.putIfAbsent(seq, data);
                }
                drainOrderedToQueueLocked();
                ackSeq = expectedRecvSeq;
            }
            try {
                sendPacket(udp, remoteAddr, TYPE_ACK, sessionId, ackSeq, null, 0);
            } catch (Exception ignored) {}
        }

        void onFinReceived(int seq) {
            synchronized (recvLock) {
                if (closed.get()) return;
                if (!finReceived) {
                    this.finSeq = seq;
                    this.finReceived = true;
                    this.finReceivedAtMs = System.currentTimeMillis();
                } else if (seq > this.finSeq) {
                    this.finSeq = seq;
                }
                drainOrderedToQueueLocked();
                checkFinDrainedLocked();
            }
        }

        private void drainOrderedToQueue() {
            int ackToSend = -1;
            synchronized (recvLock) {
                int before = expectedRecvSeq;
                drainOrderedToQueueLocked();
                if (expectedRecvSeq > before) {
                    ackToSend = expectedRecvSeq;
                }
            }
            if (ackToSend > 0 && !closed.get()) {
                try {
                    sendPacket(udp, remoteAddr, TYPE_ACK, sessionId, ackToSend, null, 0);
                } catch (Exception ignored) {}
            }
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
            if (finReceived && !finDrained && (finSeq <= 0 || expectedRecvSeq >= finSeq)) {
                finDrained = true;
            }
        }

        void onAckReceived(int nextExpected) {
            Iterator<Map.Entry<Integer, SentChunk>> it = unacked.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getKey() < nextExpected) {
                    it.remove();
                }
            }
        }

        void tickRetransmitAndTimeout() {
            if (closed.get()) return;
            long now = System.currentTimeMillis();
            if (now - lastActivityMs > SESSION_IDLE_TIMEOUT_MS) {
                FabricP2PMod.LOGGER.info("[P2P-UDP] Session {} timed out after inactivity.", sessionId);
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

            for (Map.Entry<Integer, SentChunk> entry : unacked.entrySet()) {
                SentChunk chunk = entry.getValue();
                long rto = Math.min(MAX_RTO_MS, INITIAL_RTO_MS << Math.min(chunk.retries, 4));
                if (now - chunk.lastSentMs >= rto) {
                    if (chunk.retries >= MAX_RETRANSMITS) {
                        FabricP2PMod.LOGGER.warn("[P2P-UDP] Session {} exceeded max retransmits — closing.", sessionId);
                        close();
                        return;
                    }
                    chunk.retries++;
                    chunk.lastSentMs = now;
                    try {
                        sendPacket(udp, remoteAddr, TYPE_DATA, sessionId, entry.getKey(), chunk.data, chunk.data.length);
                    } catch (Exception ignored) {}
                }
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                if (tcpSocket != null) {
                    try { tcpSocket.close(); } catch (Exception ignored) {}
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
        volatile long lastSentMs;
        volatile int retries = 0;

        SentChunk(byte[] data, long lastSentMs) {
            this.data = data;
            this.lastSentMs = lastSentMs;
        }
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

    private static void sendPacket(DatagramSocket udp, InetSocketAddress dst, byte type, int sessionId, int seq, byte[] payload, int len) throws IOException {
        int payloadLen = (payload != null) ? len : 0;
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + payloadLen);
        bb.put(MAGIC_0);
        bb.put(MAGIC_1);
        bb.put(type);
        bb.put((byte) 0);
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
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr instanceof Inet4Address) {
                return addr;
            }
        }
        return InetAddress.getByName(host);
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

    private static void sendStunKeepalive(DatagramSocket socket, String host, int port) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(20);
        req.putShort((short) 0x0001);
        req.putShort((short) 0x0000);
        req.putInt(0x2112A442);
        for (int i = 0; i < 3; i++) req.putInt(ThreadLocalRandom.current().nextInt());
        byte[] tx = req.array();
        socket.send(new DatagramPacket(tx, tx.length, resolveIpv4(host), port));
    }

    private static void publishSignal(String topic, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(SIGNAL_BASE + topic).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        conn.getResponseCode();
        conn.disconnect();
    }

    private static String randomCode(int len) {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CODE_ALPHABET.charAt(rng.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    public record HostStartResult(boolean success, String roomCode, String publicIp, int publicPort, String error) {}
    public record ClientProxyResult(boolean success, int localPort, String error) {}
}
