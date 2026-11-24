package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.config.PeerPulsarConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized, configurable registry and validator for external network endpoints
 * (Tunnel Relays, UDP Signaling servers, STUN servers, and HTTPS IP echo endpoints).
 */
public final class NetworkEndpoints {
    public static final String DEFAULT_SIGNALING_URL = "https://ntfy.sh";

    public static final List<String> DEFAULT_RELAYS = List.of("bore.pub:7835");
    public static final List<String> DEFAULT_SIGNALING = List.of(DEFAULT_SIGNALING_URL);
    public static final List<String> DEFAULT_STUN = List.of(
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun.cloudflare.com:3478",
            "stun.nextcloud.com:443"
    );
    public static final List<String> DEFAULT_IP_ECHO = List.of(
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
    );

    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("^([a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?):([0-9]{1,5})$");
    private static final Pattern IPV6_HOST_PORT_PATTERN = Pattern.compile("^\\[([0-9a-fA-F:]+)]:([0-9]{1,5})$");

    private NetworkEndpoints() {}

    public record HostPort(String host, int port) {
        public String formatted() {
            return (host.contains(":") && !host.startsWith("["))
                    ? ("[" + host + "]:" + port)
                    : (host + ":" + port);
        }
    }

    public static HostPort parseHostPort(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        Matcher v6 = IPV6_HOST_PORT_PATTERN.matcher(trimmed);
        if (v6.matches()) {
            String host = v6.group(1);
            int port = parseValidPort(v6.group(2));
            return port > 0 ? new HostPort(host, port) : null;
        }

        Matcher m = HOST_PORT_PATTERN.matcher(trimmed);
        if (m.matches()) {
            String host = m.group(1);
            if (host.contains("..")) return null;
            int port = parseValidPort(m.group(2));
            return port > 0 ? new HostPort(host.toLowerCase(Locale.ROOT), port) : null;
        }
        return null;
    }

    public static boolean isValidHostPort(String raw) {
        return parseHostPort(raw) != null;
    }

    public static String normalizeHttpsUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.contains(" ")) return null;
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) return null;
        while (trimmed.endsWith("/") && trimmed.length() > "https://".length()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || host.contains("..")) return null;
            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) return null;
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return port == -1 ? ("https://" + normalizedHost) : ("https://" + normalizedHost + ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValidHttpsUrl(String raw) {
        return normalizeHttpsUrl(raw) != null;
    }

    public static List<HostPort> getOrderedRelays(PeerPulsarConfig cfg) {
        return getOrderedHostPorts(DEFAULT_RELAYS, cfg != null ? cfg.getRelaySection() : null);
    }

    public static List<String> getOrderedSignaling(PeerPulsarConfig cfg) {
        return getOrderedUrls(DEFAULT_SIGNALING, cfg != null ? cfg.getSignalingSection() : null);
    }

    public static List<HostPort> getOrderedStun(PeerPulsarConfig cfg) {
        return getOrderedHostPorts(DEFAULT_STUN, cfg != null ? cfg.getStunSection() : null);
    }

    public static List<String> getOrderedIpEcho(PeerPulsarConfig cfg) {
        return getOrderedUrls(DEFAULT_IP_ECHO, cfg != null ? cfg.getIpEchoSection() : null);
    }

    /**
     * Selects two STUN endpoints for Dual-STUN Cone vs Symmetric NAT probing.
     * In auto mode, guarantees at least 2 distinct STUN providers when available.
     */
    public static List<HostPort> getDualStunPair(PeerPulsarConfig cfg) {
        List<HostPort> ordered = getOrderedStun(cfg);
        if (ordered.size() <= 1) return ordered;

        HostPort first = ordered.get(0);
        String firstProvider = providerKey(first.host());
        for (int i = 1; i < ordered.size(); i++) {
            HostPort candidate = ordered.get(i);
            if (!providerKey(candidate.host()).equals(firstProvider)) {
                return List.of(first, candidate);
            }
        }
        return List.of(first, ordered.get(1));
    }

    public static String providerKey(String host) {
        if (host == null) return "";
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".google.com") || lower.equals("google.com")) return "google.com";
        if (lower.endsWith(".cloudflare.com") || lower.equals("cloudflare.com")) return "cloudflare.com";
        if (lower.endsWith(".nextcloud.com") || lower.equals("nextcloud.com")) return "nextcloud.com";
        String[] parts = lower.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return lower;
    }

    private static List<HostPort> getOrderedHostPorts(List<String> defaults, PeerPulsarConfig.ServiceSection section) {
        Set<String> all = new LinkedHashSet<>(defaults);
        if (section != null) {
            for (String c : section.getCustom()) {
                HostPort hp = parseHostPort(c);
                if (hp != null) all.add(hp.formatted());
            }
        }
        List<HostPort> result = new ArrayList<>();
        if (section != null && PeerPulsarConfig.MODE_MANUAL.equals(section.getMode()) && section.getSelected() != null) {
            HostPort sel = parseHostPort(section.getSelected());
            if (sel != null) {
                result.add(sel);
            }
        }
        for (String item : all) {
            HostPort hp = parseHostPort(item);
            if (hp != null && !result.contains(hp)) {
                result.add(hp);
            }
        }
        return result;
    }

    private static List<String> getOrderedUrls(List<String> defaults, PeerPulsarConfig.ServiceSection section) {
        Set<String> all = new LinkedHashSet<>();
        for (String d : defaults) {
            String norm = normalizeHttpsUrl(d);
            if (norm != null) all.add(norm);
        }
        if (section != null) {
            for (String c : section.getCustom()) {
                String norm = normalizeHttpsUrl(c);
                if (norm != null) all.add(norm);
            }
        }
        List<String> result = new ArrayList<>();
        if (section != null && PeerPulsarConfig.MODE_MANUAL.equals(section.getMode()) && section.getSelected() != null) {
            String sel = normalizeHttpsUrl(section.getSelected());
            if (sel != null) {
                result.add(sel);
            }
        }
        for (String item : all) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private static int parseValidPort(String portStr) {
        try {
            int p = Integer.parseInt(portStr);
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static CompletableFuture<Long> pingRelayAsync(String hostPort) {
        return CompletableFuture.supplyAsync(() -> pingRelayMs(hostPort), P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    public static long pingRelayMs(String hostPort) {
        HostPort hp = parseHostPort(hostPort);
        if (hp == null) return -1;
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hp.host(), hp.port()), 2500);
            return Math.max(1L, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1;
        }
    }

    public static CompletableFuture<Long> pingSignalingAsync(String httpsUrl) {
        return CompletableFuture.supplyAsync(() -> pingHttpsMs(httpsUrl), P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    public static CompletableFuture<Long> pingIpEchoAsync(String httpsUrl) {
        return CompletableFuture.supplyAsync(() -> pingIpEchoMs(httpsUrl), P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    public static long pingHttpsMs(String httpsUrl) {
        String norm = normalizeHttpsUrl(httpsUrl);
        if (norm == null) return -1;
        HttpURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) URI.create(norm + "/").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            return (code >= 200 && code < 400) ? Math.max(1L, System.currentTimeMillis() - start) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static long pingIpEchoMs(String httpsUrl) {
        String norm = normalizeHttpsUrl(httpsUrl);
        if (norm == null) return -1;
        HttpURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) URI.create(norm).toURL().openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String ip = reader.readLine();
                if (ip != null && !ip.trim().isEmpty() && ip.trim().length() <= 45) {
                    return Math.max(1L, System.currentTimeMillis() - start);
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static CompletableFuture<Long> pingStunAsync(String hostPort) {
        return CompletableFuture.supplyAsync(() -> pingStunMs(hostPort), P2PNetworkManager.NETWORK_IO_EXECUTOR);
    }

    public static long pingStunMs(String hostPort) {
        HostPort hp = parseHostPort(hostPort);
        if (hp == null) return -1;
        long start = System.currentTimeMillis();
        try (DatagramSocket sock = new DatagramSocket()) {
            InetSocketAddress mapped = UdpHolePunchService.queryStunOnSocket(sock, hp.host(), hp.port());
            return mapped != null ? Math.max(1L, System.currentTimeMillis() - start) : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
