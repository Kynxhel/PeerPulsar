package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.config.PeerPulsarConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private volatile CompletableFuture<DiagnosticReport> inFlightCheck = null;

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

    /**
     * Runs diagnostics asynchronously. If a diagnostic run is already in progress,
     * returns the in-flight CompletableFuture so callers wait for the actual result.
     */
    public synchronized CompletableFuture<DiagnosticReport> runDiagnosticsAsync() {
        if (status == P2PCompatibilityStatus.TESTING && inFlightCheck != null && !inFlightCheck.isDone()) {
            return inFlightCheck;
        }

        this.status = P2PCompatibilityStatus.TESTING;

        CompletableFuture<DiagnosticReport> future = CompletableFuture.supplyAsync(() -> {
            PeerPulsar.LOGGER.info("[P2P-Diag] Starting internet, NAT (Dual-STUN), UPnP & Tunnel diagnostics...");

            // 1. Dual-STUN check on the SAME UDP socket (IPv4) to detect Public IP + Cone NAT vs Symmetric NAT
            String publicIp = null;
            boolean coneNatSupported = false;
            List<NetworkEndpoints.HostPort> stunPair = NetworkEndpoints.getDualStunPair(PeerPulsarConfig.get());
            try (DatagramSocket stunSock = new DatagramSocket()) {
                InetSocketAddress r1 = stunPair.size() >= 1
                        ? UdpHolePunchService.queryStunOnSocket(stunSock, stunPair.get(0).host(), stunPair.get(0).port())
                        : null;
                InetSocketAddress r2 = stunPair.size() >= 2
                        ? UdpHolePunchService.queryStunOnSocket(stunSock, stunPair.get(1).host(), stunPair.get(1).port())
                        : null;
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
                PeerPulsar.LOGGER.warn("[P2P-Diag] No internet connection detected.");
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

            // 3. Detect CGNAT (RFC 6598 100.64.0.0/10, RFC 1918 private WAN, or IPv4 WAN != Public IPv4)
            boolean cgnat = false;
            if (routerWanIp != null && !routerWanIp.isEmpty()) {
                if (isCgnatOrPrivateIp(routerWanIp)
                        || (isIpv4Literal(publicIp) && isIpv4Literal(routerWanIp) && !routerWanIp.equals(publicIp))) {
                    cgnat = true;
                }
            }

            // 4. Test Fallback Tunnel reachability & latency
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

            PeerPulsar.LOGGER.info(
                    "[P2P-Diag] Result={} | PublicIP={} | RouterWAN={} | UPnP={} (Map={}) | CGNAT={} | ConeNAT(UDP)={} | Tunnel={} ({}ms)",
                    finalStatus, publicIp, routerWanIp, probe.upnpFound(), probe.portMappingWorks(),
                    cgnat, coneNatSupported, tunnelAvailable, tunnelLatency
            );
            return report;
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR);

        this.inFlightCheck = future;
        future.whenComplete((r, err) -> {
            synchronized (NetworkDiagnosticsService.this) {
                if (this.inFlightCheck == future) {
                    this.inFlightCheck = null;
                }
                if (err != null) {
                    PeerPulsar.LOGGER.warn("[P2P-Diag] Diagnostics failed unexpectedly: {}", err.getMessage());
                    if (this.status == P2PCompatibilityStatus.TESTING) {
                        this.status = P2PCompatibilityStatus.IDLE;
                    }
                }
            }
        });
        return future;
    }

    /**
     * Checks whether a string is a strict dotted-quad IPv4 literal (0.0.0.0 - 255.255.255.255).
     */
    public static boolean isIpv4Literal(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int val = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                val = val * 10 + (c - '0');
            }
            if (val > 255) return false;
        }
        return true;
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
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 127 || a == 0) return true;
        } catch (NumberFormatException ignored) {}
        return false;
    }

    private static String queryHttpsPublicIp() {
        for (String endpoint : NetworkEndpoints.getOrderedIpEcho(PeerPulsarConfig.get())) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String ip = reader.readLine();
                    if (ip != null && !ip.trim().isEmpty() && ip.trim().length() <= 45) {
                        return ip.trim();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
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
