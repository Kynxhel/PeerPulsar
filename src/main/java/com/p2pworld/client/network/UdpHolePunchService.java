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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java Essential-style UDP Hole Punching P2P engine with Reliable Ordered UDP (RUDP).
 * Uses Dual-STUN for NAT traversal and ntfy.sh HTTPS streaming for zero-config peer signaling,
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

    private static final byte TYPE_PUNCH = 1;
    private static final byte TYPE_PUNCH_ACK = 2;
    private static final byte TYPE_DATA = 3;
    private static final byte TYPE_ACK = 4;
    private static final byte TYPE_FIN = 5;
    private static final byte TYPE_KEEPALIVE = 6;

    private static UdpHolePunchService instance;

    // Host state
    private volatile boolean hostActive = false;
    private volatile String hostRoomCode = null;
    private volatile String hostTopic = null;
    private volatile String hostPublicIp = null;
    private volatile int hostPublicPort = -1;
    private volatile DatagramSocket hostUdpSocket = null;
    private volatile HttpURLConnection hostSignalConn = null;
    private volatile ScheduledExecutorService hostScheduler = null;
    private final Map<Integer, PeerSession> hostSessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> addrToSessionId = new ConcurrentHashMap<>();

    // Client join proxy state
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
            try {
                DatagramSocket udp = new DatagramSocket();
                udp.setReceiveBufferSize(512 * 1024);
                udp.setSendBufferSize(512 * 1024);

                InetSocketAddress mapped = queryStunOnSocket(udp, STUN_HOST, STUN_PORT);
                if (mapped == null) {
                    udp.close();
                    return new HostStartResult(false, null, null, -1, "STUN mapping failed");
                }

                String rawId = randomCode(12);
                String roomCode = "UDP-" + rawId.substring(0, 4) + "-" + rawId.substring(4, 8) + "-" + rawId.substring(8, 12);
                String topic = "fp2p-" + rawId.toLowerCase();

                this.hostUdpSocket = udp;
                this.hostPublicIp = mapped.getAddress().getHostAddress();
                this.hostPublicPort = mapped.getPort();
                this.hostRoomCode = roomCode;
                this.hostTopic = topic;
                this.hostActive = true;

                this.hostScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "FabricP2P-UDP-Host-Timer");
                    t.setDaemon(true);
                    return t;
                });

                // Keep CGNAT UDP port mapping alive & handle retransmits
                this.hostScheduler.scheduleAtFixedRate(() -> {
                    if (!hostActive) return;
                    try {
                        sendStunKeepalive(udp, STUN_HOST, STUN_PORT);
                    } catch (Exception ignored) {}
                }, 12, 12, TimeUnit.SECONDS);

                this.hostScheduler.scheduleAtFixedRate(() -> {
                    if (!hostActive) return;
                    for (PeerSession session : hostSessions.values()) {
                        session.tickRetransmit();
                    }
                }, 40, 40, TimeUnit.MILLISECONDS);

                // UDP receive loop
                Thread recvThread = new Thread(() -> runHostUdpLoop(udp, localMcPort), "FabricP2P-UDP-Host-Recv");
                recvThread.setDaemon(true);
                recvThread.start();

                // Signaling listener loop (ntfy.sh streaming NDJSON)
                Thread signalThread = new Thread(() -> runHostSignalLoop(topic, udp), "FabricP2P-UDP-Host-Signal");
                signalThread.setDaemon(true);
                signalThread.start();

                FabricP2PMod.LOGGER.info("[P2P-UDP] Host ready! RoomCode={} | STUN={}:{} -> 127.0.0.1:{}",
                        roomCode, hostPublicIp, hostPublicPort, localMcPort);
                return new HostStartResult(true, roomCode, hostPublicIp, hostPublicPort, null);
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[P2P-UDP] Failed to start UDP host: {}", e.getMessage());
                stopHost();
                return new HostStartResult(false, null, null, -1, e.getMessage());
            }
        });
    }

    private void runHostSignalLoop(String topic, DatagramSocket udp) {
        while (hostActive) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(SIGNAL_BASE + topic + "/json").toURL().openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(0);
                this.hostSignalConn = conn;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (hostActive && (line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        try {
                            JsonObject env = JsonParser.parseString(line).getAsJsonObject();
                            if (!"message".equals(env.has("event") ? env.get("event").getAsString() : "")) continue;
                            String msgStr = env.get("message").getAsString();
                            JsonObject payload = JsonParser.parseString(msgStr).getAsJsonObject();
                            if (!"join".equals(payload.has("role") ? payload.get("role").getAsString() : "")) continue;

                            String peerId = payload.get("id").getAsString();
                            String peerIp = payload.get("ip").getAsString();
                            int peerPort = payload.get("port").getAsInt();
                            int sessionId = payload.get("sid").getAsInt();

                            // Reply with our fresh STUN endpoint
                            String reply = String.format(
                                    "{\"role\":\"host\",\"to\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"sid\":%d}",
                                    peerId, hostPublicIp, hostPublicPort, sessionId
                            );
                            publishSignal(topic, reply);

                            // Actively punch toward the joiner's CGNAT endpoint
                            InetSocketAddress peerAddr = new InetSocketAddress(InetAddress.getByName(peerIp), peerPort);
                            addrToSessionId.put(peerIp + ":" + peerPort, sessionId);
                            Thread punchThread = new Thread(() -> {
                                for (int i = 0; i < 25 && hostActive; i++) {
                                    try {
                                        sendPacket(udp, peerAddr, TYPE_PUNCH, sessionId, 0, null, 0);
                                        Thread.sleep(100);
                                    } catch (Exception ignored) {
                                        break;
                                    }
                                }
                            }, "FabricP2P-UDP-PunchOut");
                            punchThread.setDaemon(true);
                            punchThread.start();
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                if (hostActive) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    private void runHostUdpLoop(DatagramSocket udp, int localMcPort) {
        byte[] buf = new byte[2048];
        while (hostActive && !udp.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udp.receive(pkt);
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
                    if (session == null && type == TYPE_DATA) {
                        session = getOrCreateHostSession(sessionId, udp, remoteAddr, localMcPort);
                    }
                    if (session != null) {
                        session.updateRemoteAddress(remoteAddr);
                        handleSessionPacket(session, type, seq, buf, HEADER_SIZE, Math.min(payloadLen, len - HEADER_SIZE));
                    }
                }
            } catch (IOException e) {
                if (hostActive) {
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
        this.hostActive = false;
        this.hostRoomCode = null;
        this.hostTopic = null;
        this.hostPublicIp = null;
        this.hostPublicPort = -1;

        if (hostScheduler != null) {
            hostScheduler.shutdownNow();
            hostScheduler = null;
        }
        if (hostSignalConn != null) {
            try { hostSignalConn.disconnect(); } catch (Exception ignored) {}
            hostSignalConn = null;
        }
        for (PeerSession s : hostSessions.values()) {
            s.close();
        }
        hostSessions.clear();
        addrToSessionId.clear();

        if (hostUdpSocket != null) {
            try { hostUdpSocket.close(); } catch (Exception ignored) {}
            hostUdpSocket = null;
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
            try {
                String clean = udpRoomCode.toUpperCase().replace("UDP-", "").replace("-", "").trim();
                if (clean.length() < 8) {
                    return new ClientProxyResult(false, -1, "Invalid UDP room code");
                }
                String topic = "fp2p-" + clean.toLowerCase();
                String peerId = randomCode(8);
                int sessionId = ThreadLocalRandom.current().nextInt(100_000, Integer.MAX_VALUE);

                DatagramSocket udp = new DatagramSocket();
                udp.setReceiveBufferSize(512 * 1024);
                udp.setSendBufferSize(512 * 1024);

                InetSocketAddress myMapped = queryStunOnSocket(udp, STUN_HOST, STUN_PORT);
                if (myMapped == null) {
                    udp.close();
                    return new ClientProxyResult(false, -1, "STUN discovery failed on client");
                }

                // Subscribe to signaling BEFORE publishing join request
                CompletableFuture<InetSocketAddress> hostAddrFuture = new CompletableFuture<>();
                HttpURLConnection signalConn = (HttpURLConnection) URI.create(SIGNAL_BASE + topic + "/json").toURL().openConnection();
                signalConn.setConnectTimeout(5000);
                signalConn.setReadTimeout(8000);

                Thread sigReader = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(signalConn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!hostAddrFuture.isDone() && (line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonObject env = JsonParser.parseString(line).getAsJsonObject();
                                if (!"message".equals(env.has("event") ? env.get("event").getAsString() : "")) continue;
                                JsonObject payload = JsonParser.parseString(env.get("message").getAsString()).getAsJsonObject();
                                if ("host".equals(payload.has("role") ? payload.get("role").getAsString() : "")
                                        && peerId.equals(payload.has("to") ? payload.get("to").getAsString() : "")) {
                                    String hIp = payload.get("ip").getAsString();
                                    int hPort = payload.get("port").getAsInt();
                                    hostAddrFuture.complete(new InetSocketAddress(InetAddress.getByName(hIp), hPort));
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        hostAddrFuture.completeExceptionally(e);
                    }
                }, "FabricP2P-UDP-ClientSignal");
                sigReader.setDaemon(true);
                sigReader.start();

                Thread.sleep(300); // Ensure subscription is active
                String joinMsg = String.format(
                        "{\"role\":\"join\",\"id\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"sid\":%d}",
                        peerId, myMapped.getAddress().getHostAddress(), myMapped.getPort(), sessionId
                );
                publishSignal(topic, joinMsg);

                InetSocketAddress hostAddr;
                try {
                    hostAddr = hostAddrFuture.get(7, TimeUnit.SECONDS);
                } finally {
                    try { signalConn.disconnect(); } catch (Exception ignored) {}
                }

                // Perform UDP Hole Punching handshake with Host
                udp.setSoTimeout(150);
                boolean punched = false;
                byte[] rxBuf = new byte[2048];
                long punchDeadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < punchDeadline) {
                    sendPacket(udp, hostAddr, TYPE_PUNCH, sessionId, 0, null, 0);
                    try {
                        DatagramPacket rx = new DatagramPacket(rxBuf, rxBuf.length);
                        udp.receive(rx);
                        if (rx.getLength() >= HEADER_SIZE && rxBuf[0] == MAGIC_0 && rxBuf[1] == MAGIC_1) {
                            byte type = rxBuf[2];
                            if (type == TYPE_PUNCH || type == TYPE_PUNCH_ACK) {
                                sendPacket(udp, hostAddr, TYPE_PUNCH_ACK, sessionId, 0, null, 0);
                                punched = true;
                                break;
                            }
                        }
                    } catch (IOException ignored) {}
                }

                if (!punched) {
                    udp.close();
                    return new ClientProxyResult(false, -1, "UDP hole punch timed out (Symmetric NAT?)");
                }

                udp.setSoTimeout(0);
                ServerSocket localServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                int localProxyPort = localServer.getLocalPort();

                this.clientUdpSocket = udp;
                this.clientLocalServer = localServer;
                this.clientActive = true;

                PeerSession session = new PeerSession(sessionId, udp, hostAddr, this::stopClientProxy);
                this.clientSession = session;

                this.clientScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "FabricP2P-UDP-Client-Timer");
                    t.setDaemon(true);
                    return t;
                });
                this.clientScheduler.scheduleAtFixedRate(() -> {
                    if (clientActive && clientSession != null) {
                        clientSession.tickRetransmit();
                    }
                }, 40, 40, TimeUnit.MILLISECONDS);
                this.clientScheduler.scheduleAtFixedRate(() -> {
                    if (clientActive && !udp.isClosed()) {
                        try {
                            sendPacket(udp, hostAddr, TYPE_KEEPALIVE, sessionId, 0, null, 0);
                        } catch (Exception ignored) {}
                    }
                }, 8, 8, TimeUnit.SECONDS);

                // Client UDP receive loop
                Thread recvThread = new Thread(() -> {
                    byte[] b = new byte[2048];
                    while (clientActive && !udp.isClosed()) {
                        try {
                            DatagramPacket p = new DatagramPacket(b, b.length);
                            udp.receive(p);
                            int len = p.getLength();
                            if (len < HEADER_SIZE || b[0] != MAGIC_0 || b[1] != MAGIC_1) continue;
                            ByteBuffer bb = ByteBuffer.wrap(b, 0, len);
                            bb.position(2);
                            byte type = bb.get();
                            bb.get();
                            int sid = bb.getInt();
                            int seq = bb.getInt();
                            int pLen = bb.getShort() & 0xFFFF;
                            if (sid == sessionId) {
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
                        Socket mcSock = localServer.accept();
                        mcSock.setTcpNoDelay(true);
                        session.attachTcpSocket(mcSock);
                    } catch (IOException ignored) {}
                }, "FabricP2P-UDP-Client-Accept");
                acceptThread.setDaemon(true);
                acceptThread.start();

                FabricP2PMod.LOGGER.info("[P2P-UDP] Direct UDP P2P punched to {}! Local MC proxy on 127.0.0.1:{}",
                        hostAddr, localProxyPort);
                return new ClientProxyResult(true, localProxyPort, null);
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[P2P-UDP] Client hole punch error: {}", e.getMessage());
                stopClientProxy();
                return new ClientProxyResult(false, -1, e.getMessage());
            }
        });
    }

    public void stopClientProxy() {
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

    // ==================== RELIABLE UDP STREAM (RUDP) ====================

    private static void handleSessionPacket(PeerSession session, byte type, int seq, byte[] buf, int offset, int len) {
        if (type == TYPE_DATA) {
            byte[] copy = new byte[len];
            System.arraycopy(buf, offset, copy, 0, len);
            session.onDataReceived(seq, copy);
        } else if (type == TYPE_ACK) {
            session.onAckReceived(seq);
        } else if (type == TYPE_FIN) {
            session.close();
        }
    }

    private static final class PeerSession {
        final int sessionId;
        final DatagramSocket udp;
        volatile InetSocketAddress remoteAddr;
        final Runnable onClose;
        final AtomicBoolean closed = new AtomicBoolean(false);

        volatile Socket tcpSocket;
        final AtomicInteger nextSendSeq = new AtomicInteger(0);
        final Map<Integer, SentChunk> unacked = new ConcurrentHashMap<>();

        final Object recvLock = new Object();
        int expectedRecvSeq = 0;
        final TreeMap<Integer, byte[]> reorderBuffer = new TreeMap<>();

        PeerSession(int sessionId, DatagramSocket udp, InetSocketAddress remoteAddr, Runnable onClose) {
            this.sessionId = sessionId;
            this.udp = udp;
            this.remoteAddr = remoteAddr;
            this.onClose = onClose;
        }

        void updateRemoteAddress(InetSocketAddress addr) {
            this.remoteAddr = addr;
        }

        void attachTcpSocket(Socket tcp) {
            this.tcpSocket = tcp;
            // Flush any data that arrived before TCP socket was ready
            flushOrderedData();

            Thread tcpReader = new Thread(() -> {
                byte[] buf = new byte[MAX_PAYLOAD];
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
                } catch (Exception ignored) {
                } finally {
                    try {
                        sendPacket(udp, remoteAddr, TYPE_FIN, sessionId, 0, null, 0);
                    } catch (Exception ignored) {}
                    close();
                }
            }, "FabricP2P-RUDP-Send-" + sessionId);
            tcpReader.setDaemon(true);
            tcpReader.start();
        }

        void onDataReceived(int seq, byte[] data) {
            synchronized (recvLock) {
                if (seq >= expectedRecvSeq) {
                    reorderBuffer.putIfAbsent(seq, data);
                }
                flushOrderedDataLocked();
                try {
                    sendPacket(udp, remoteAddr, TYPE_ACK, sessionId, expectedRecvSeq, null, 0);
                } catch (Exception ignored) {}
            }
        }

        void flushOrderedData() {
            synchronized (recvLock) {
                flushOrderedDataLocked();
            }
        }

        private void flushOrderedDataLocked() {
            Socket tcp = this.tcpSocket;
            if (tcp == null || tcp.isClosed()) return;
            try {
                OutputStream out = tcp.getOutputStream();
                boolean wrote = false;
                while (reorderBuffer.containsKey(expectedRecvSeq)) {
                    byte[] chunk = reorderBuffer.remove(expectedRecvSeq);
                    out.write(chunk);
                    expectedRecvSeq++;
                    wrote = true;
                }
                if (wrote) out.flush();
            } catch (IOException e) {
                close();
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

        void tickRetransmit() {
            if (closed.get() || unacked.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, SentChunk> entry : unacked.entrySet()) {
                SentChunk chunk = entry.getValue();
                if (now - chunk.lastSentMs >= 80) {
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
                if (onClose != null) onClose.run();
            }
        }
    }

    private static final class SentChunk {
        final byte[] data;
        volatile long lastSentMs;

        SentChunk(byte[] data, long lastSentMs) {
            this.data = data;
            this.lastSentMs = lastSentMs;
        }
    }

    // ==================== STUN & SIGNALING HELPERS ====================

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

    public static InetSocketAddress queryStunOnSocket(DatagramSocket socket, String host, int port) {
        try {
            int oldTimeout = socket.getSoTimeout();
            socket.setSoTimeout(2000);
            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) 0x0001);
            req.putShort((short) 0x0000);
            req.putInt(0x2112A442);
            for (int i = 0; i < 3; i++) req.putInt(ThreadLocalRandom.current().nextInt());
            byte[] tx = req.array();
            socket.send(new DatagramPacket(tx, tx.length, InetAddress.getByName(host), port));

            byte[] rx = new byte[512];
            DatagramPacket rxPkt = new DatagramPacket(rx, rx.length);
            socket.receive(rxPkt);
            socket.setSoTimeout(oldTimeout);

            ByteBuffer resp = ByteBuffer.wrap(rxPkt.getData(), 0, rxPkt.getLength());
            if (resp.remaining() < 20) return null;
            short msgType = resp.getShort();
            resp.getShort();
            int cookie = resp.getInt();
            if (msgType != (short) 0x0101 || cookie != 0x2112A442) return null;

            resp.position(20);
            while (resp.remaining() >= 4) {
                short attrType = resp.getShort();
                int attrLen = resp.getShort() & 0xFFFF;
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
                } else {
                    int padded = (attrLen + 3) & ~3;
                    resp.position(Math.min(resp.limit(), resp.position() + padded));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void sendStunKeepalive(DatagramSocket socket, String host, int port) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(20);
        req.putShort((short) 0x0001);
        req.putShort((short) 0x0000);
        req.putInt(0x2112A442);
        for (int i = 0; i < 3; i++) req.putInt(ThreadLocalRandom.current().nextInt());
        byte[] tx = req.array();
        socket.send(new DatagramPacket(tx, tx.length, InetAddress.getByName(host), port));
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
