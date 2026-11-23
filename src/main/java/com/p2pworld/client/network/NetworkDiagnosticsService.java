package com.p2pworld.client.network;

import com.p2pworld.FabricP2PMod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Diagnoses the host's internet connection, NAT/CGNAT status, UPnP capability,
 * and fallback TCP tunnel reachability to determine whether direct P2P hosting
 * is supported or an automatic fallback solution is required.
 */
public class NetworkDiagnosticsService {
    private static NetworkDiagnosticsService instance;

    private volatile P2PCompatibilityStatus status = P2PCompatibilityStatus.IDLE;
    private volatile DiagnosticReport lastReport = DiagnosticReport.empty();
    private volatile long lastCheckMs = 0;

    private static final String[] IP_ENDPOINTS = {
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
    };

    private NetworkDiagnosticsService() {}

    public static synchronized NetworkDiagnosticsService getInstance() {
        if (instance == null) {
            instance = new NetworkDiagnosticsService();
        }
        return instance;
    }

    public enum P2PCompatibilityStatus {
        IDLE,
        TESTING,
        DIRECT_SUPPORTED,
        CGNAT_DETECTED,
        UPNP_UNAVAILABLE,
        NO_INTERNET
    }

    public P2PCompatibilityStatus getStatus() {
        return status;
    }

    public DiagnosticReport getLastReport() {
        return lastReport;
    }

    public boolean isTesting() {
        return status == P2PCompatibilityStatus.TESTING;
    }

    public boolean hasRecentReport() {
        return status != P2PCompatibilityStatus.IDLE
                && status != P2PCompatibilityStatus.TESTING
                && (System.currentTimeMillis() - lastCheckMs) < 90_000;
    }

    public boolean canHostAnyMethod() {
        if (status == P2PCompatibilityStatus.DIRECT_SUPPORTED) {
            return true;
        }
        if (status == P2PCompatibilityStatus.CGNAT_DETECTED || status == P2PCompatibilityStatus.UPNP_UNAVAILABLE) {
            return lastReport.udpHolePunchSupported() || lastReport.tunnelAvailable();
        }
        return false;
    }

    public boolean shouldUseTunnelFallback() {
        return (status == P2PCompatibilityStatus.CGNAT_DETECTED || status == P2PCompatibilityStatus.UPNP_UNAVAILABLE)
                && !lastReport.udpHolePunchSupported()
                && lastReport.tunnelAvailable();
    }

    public CompletableFuture<DiagnosticReport> runDiagnosticsAsync() {
        if (status == P2PCompatibilityStatus.TESTING) {
            return CompletableFuture.completedFuture(lastReport);
        }

        this.status = P2PCompatibilityStatus.TESTING;

        return CompletableFuture.supplyAsync(() -> {
            FabricP2PMod.LOGGER.info("[P2P-Diag] Starting internet, NAT (Dual-STUN), UPnP & Tunnel diagnostics...");

            // 1. Dual-STUN check on the SAME UDP socket to detect Public IP + Cone NAT vs Symmetric NAT
            String publicIp = null;
            boolean coneNatSupported = false;
            try (DatagramSocket stunSock = new DatagramSocket()) {
                java.net.InetSocketAddress r1 = UdpHolePunchService.queryStunOnSocket(stunSock, "stun.l.google.com", 19302);
                java.net.InetSocketAddress r2 = UdpHolePunchService.queryStunOnSocket(stunSock, "stun.cloudflare.com", 3478);
                if (r1 != null) {
                    publicIp = r1.getAddress().getHostAddress();
                } else if (r2 != null) {
                    publicIp = r2.getAddress().getHostAddress();
                }
                if (r1 != null && r2 != null
                        && r1.getAddress().equals(r2.getAddress())
                        && r1.getPort() == r2.getPort()) {
                    coneNatSupported = true;
                }
            } catch (Exception ignored) {}

            if (publicIp == null) {
                publicIp = queryHttpsPublicIp();
            }

            if (publicIp == null) {
                FabricP2PMod.LOGGER.warn("[P2P-Diag] No internet connection detected.");
                DiagnosticReport report = new DiagnosticReport(
                        P2PCompatibilityStatus.NO_INTERNET,
                        null, null, false, false, false, false, false, -1
                );
                this.lastReport = report;
                this.status = P2PCompatibilityStatus.NO_INTERNET;
                this.lastCheckMs = System.currentTimeMillis();
                return report;
            }

            // 2. Probe UPnP Router, WAN IP, and test AddPortMapping/DeletePortMapping
            UPnPService.GatewayProbeResult probe = UPnPService.getInstance().probeGateway(true);
            String routerWanIp = probe.routerWanIp();

            // 3. Detect CGNAT (RFC 6598 100.64.0.0/10, RFC 1918 private WAN, or WAN != Public IP)
            boolean cgnat = false;
            if (routerWanIp != null && !routerWanIp.isEmpty()) {
                if (isCgnatOrPrivateIp(routerWanIp) || !routerWanIp.equals(publicIp)) {
                    cgnat = true;
                }
            }

            // 4. Test Fallback Tunnel (bore.pub) reachability & latency
            long tunnelLatency = TunnelRelayService.testRelayLatencyMs();
            boolean tunnelAvailable = tunnelLatency >= 0;

            // 5. Decide final compatibility status
            P2PCompatibilityStatus finalStatus;
            if (cgnat) {
                finalStatus = P2PCompatibilityStatus.CGNAT_DETECTED;
            } else if (!probe.upnpFound() || !probe.portMappingWorks()) {
                finalStatus = P2PCompatibilityStatus.UPNP_UNAVAILABLE;
            } else {
                finalStatus = P2PCompatibilityStatus.DIRECT_SUPPORTED;
            }

            DiagnosticReport report = new DiagnosticReport(
                    finalStatus,
                    publicIp,
                    routerWanIp,
                    probe.upnpFound(),
                    probe.portMappingWorks(),
                    cgnat,
                    coneNatSupported,
                    tunnelAvailable,
                    tunnelLatency
            );

            this.lastReport = report;
            this.status = finalStatus;
            this.lastCheckMs = System.currentTimeMillis();

            FabricP2PMod.LOGGER.info(
                    "[P2P-Diag] Result={} | PublicIP={} | RouterWAN={} | UPnP={} (Map={}) | CGNAT={} | ConeNAT(UDP)={} | Tunnel={} ({}ms)",
                    finalStatus, publicIp, routerWanIp, probe.upnpFound(), probe.portMappingWorks(),
                    cgnat, coneNatSupported, tunnelAvailable, tunnelLatency
            );
            return report;
        });
    }

    /**
     * Checks whether an IPv4 address belongs to CGNAT (100.64.0.0/10) or private RFC1918 ranges.
     */
    public static boolean isCgnatOrPrivateIp(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 100 && b >= 64 && b <= 127) return true; // RFC 6598 CGNAT
            if (a == 10) return true;                         // 10.0.0.0/8
            if (a == 172 && b >= 16 && b <= 31) return true;  // 172.16.0.0/12
            if (a == 192 && b == 168) return true;            // 192.168.0.0/16
            if (a == 127 || a == 0) return true;              // Loopback / unspecified
        } catch (NumberFormatException ignored) {}
        return false;
    }

    private static String queryStunPublicIp(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1800);
            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) 0x0001); // Binding Request
            req.putShort((short) 0x0000); // Length
            req.putInt(0x2112A442);       // RFC 5389 Magic Cookie
            for (int i = 0; i < 3; i++) {
                req.putInt(ThreadLocalRandom.current().nextInt());
            }

            byte[] tx = req.array();
            socket.send(new DatagramPacket(tx, tx.length, InetAddress.getByName(host), port));

            byte[] rx = new byte[512];
            DatagramPacket rxPacket = new DatagramPacket(rx, rx.length);
            socket.receive(rxPacket);

            ByteBuffer resp = ByteBuffer.wrap(rxPacket.getData(), 0, rxPacket.getLength());
            if (resp.remaining() < 20) return null;
            short msgType = resp.getShort();
            resp.getShort(); // length
            int cookie = resp.getInt();
            if (msgType != (short) 0x0101 || cookie != 0x2112A442) return null;

            resp.position(20);
            while (resp.remaining() >= 4) {
                short attrType = resp.getShort();
                int attrLen = resp.getShort() & 0xFFFF;
                if (resp.remaining() < attrLen) break;

                if (attrType == 0x0020 && attrLen >= 8) { // XOR-MAPPED-ADDRESS
                    resp.get(); // reserved
                    byte family = resp.get();
                    resp.getShort(); // xor port
                    if (family == 0x01) { // IPv4
                        byte[] ipBytes = new byte[4];
                        resp.get(ipBytes);
                        for (int i = 0; i < 4; i++) {
                            ipBytes[i] = (byte) (ipBytes[i] ^ ((0x2112A442 >> (24 - i * 8)) & 0xFF));
                        }
                        return InetAddress.getByAddress(ipBytes).getHostAddress();
                    }
                } else {
                    int padded = (attrLen + 3) & ~3;
                    resp.position(Math.min(resp.limit(), resp.position() + padded));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String queryHttpsPublicIp() {
        for (String endpoint : IP_ENDPOINTS) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ip = reader.readLine();
                    if (ip != null && !ip.trim().isEmpty() && ip.trim().length() <= 45) {
                        return ip.trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public record DiagnosticReport(
            P2PCompatibilityStatus status,
            String publicIp,
            String routerWanIp,
            boolean upnpFound,
            boolean upnpPortMapWorks,
            boolean cgnatDetected,
            boolean udpHolePunchSupported,
            boolean tunnelAvailable,
            long tunnelLatencyMs
    ) {
        public static DiagnosticReport empty() {
            return new DiagnosticReport(P2PCompatibilityStatus.IDLE, null, null, false, false, false, false, false, -1);
        }
    }
}
