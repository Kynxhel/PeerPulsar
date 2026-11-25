package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.config.PeerPulsarConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

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

    /**
     * Purely lexical check (no DNS) that rejects null/blank hosts, local/internal domains,
     * single-label hostnames, and non-public IPv4/IPv6 literals.
     */
    public static boolean isNonPublicHostLiteralOrName(String host) {
        if (host == null) return true;
        String trimmed = host.trim();
        if (trimmed.isEmpty()) return true;

        boolean bracketed = trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2;
        String unbracketed = bracketed ? trimmed.substring(1, trimmed.length() - 1) : trimmed;

        // Zone IDs (e.g. fe80::1%eth0) always denote a scoped/non-public address
        if (unbracketed.indexOf('%') >= 0) {
            return true;
        }

        try {
            InetAddress literal = InetAddress.ofLiteral(unbracketed);
            return isNonPublicAddress(literal);
        } catch (IllegalArgumentException ignored) {
            if (bracketed) {
                return true;
            }
        }

        String lower = unbracketed.toLowerCase(Locale.ROOT);
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.isEmpty()) return true;

        if (lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || lower.endsWith(".lan")
                || lower.endsWith(".home.arpa")) {
            return true;
        }

        return !lower.contains(".");
    }

    /**
     * Returns true if {@code addr} is null or falls in any non-public range
     * (any-local, loopback, link-local, site-local/RFC1918, multicast, CGNAT 100.64.0.0/10,
     * IPv6 ULA fc00::/7, or IPv4-mapped IPv6 wrapping a non-public IPv4 address).
     */
    public static boolean isNonPublicAddress(InetAddress addr) {
        if (addr == null) return true;
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            // CGNAT 100.64.0.0/10
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // "This" network 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
        } else if (b.length == 16) {
            // IPv6 ULA fc00::/7
            if ((b[0] & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped IPv6 ::ffff:0:0/96
            if (isIpv4MappedIpv6(b)) {
                try {
                    byte[] v4 = new byte[4];
                    System.arraycopy(b, 12, v4, 0, 4);
                    return isNonPublicAddress(InetAddress.getByAddress(v4));
                } catch (Exception ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIpv4MappedIpv6(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    /**
     * Normalizes an HTTPS URL and rejects non-public hosts/literals (SSRF defense).
     */
    public static String normalizePublicHttpsUrl(String raw) {
        String norm = normalizeHttpsUrl(raw);
        if (norm == null) return null;
        String host = URI.create(norm).getHost();
        return isNonPublicHostLiteralOrName(host) ? null : norm;
    }

    /**
     * Runtime DNS-rebinding check: ensures the host is lexically public and every resolved
     * address is a public IP address.
     */
    public static boolean resolvesToPublicAddress(String host) {
        if (isNonPublicHostLiteralOrName(host)) return false;
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(trimmed);
            if (addrs == null || addrs.length == 0) return false;
            for (InetAddress addr : addrs) {
                if (isNonPublicAddress(addr)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripBrackets(String s) {
        if (s != null && s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Opens an {@link HttpsURLConnection} with redirects disabled and DNS pinned to verified
     * public IP addresses to prevent HTTP redirect SSRF and DNS rebinding attacks.
     */
    public static HttpsURLConnection openPinnedPublicHttpsConnection(String fullUrl, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (fullUrl == null || fullUrl.isBlank()) {
            throw new IOException("Blank HTTPS URL");
        }
        final URI uri;
        try {
            uri = URI.create(fullUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid HTTPS URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTPS URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IOException("URL userinfo is not allowed");
        }
        String rawHost = uri.getHost();
        if (isNonPublicHostLiteralOrName(rawHost)) {
            throw new IOException("Rejected non-public host: " + rawHost);
        }
        final String host = stripBrackets(rawHost.trim());
        final InetAddress[] pinnedAddrs = InetAddress.getAllByName(host);
        if (pinnedAddrs == null || pinnedAddrs.length == 0) {
            throw new IOException("Failed to resolve host: " + host);
        }
        for (InetAddress addr : pinnedAddrs) {
            if (isNonPublicAddress(addr)) {
                throw new IOException("Rejected non-public resolved address for host " + host);
            }
        }

        URLConnection rawConn = uri.toURL().openConnection();
        if (!(rawConn instanceof HttpsURLConnection conn)) {
            throw new IOException("Expected HttpsURLConnection");
        }
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);

        final SSLSocketFactory delegate = HttpsURLConnection.getDefaultSSLSocketFactory();
        final Set<InetAddress> pinnedSet = new LinkedHashSet<>(Arrays.asList(pinnedAddrs));
        conn.setSSLSocketFactory(new SSLSocketFactory() {
            private Socket connectPinnedRawSocket(InetAddress preferredAddr, InetAddress localAddr, int localPort, int port) throws IOException {
                IOException lastErr = null;
                List<InetAddress> candidates = new ArrayList<>(pinnedAddrs.length);
                if (preferredAddr != null) {
                    if (isNonPublicAddress(preferredAddr) || !pinnedSet.contains(preferredAddr)) {
                        throw new IOException("Rejected unpinned or non-public target address");
                    }
                    candidates.add(preferredAddr);
                } else {
                    candidates.addAll(Arrays.asList(pinnedAddrs));
                }
                for (InetAddress target : candidates) {
                    if (isNonPublicAddress(target)) {
                        throw new IOException("Rejected non-public target address");
                    }
                    Socket raw = new Socket();
                    try {
                        if (localAddr != null) {
                            raw.bind(new InetSocketAddress(localAddr, localPort));
                        }
                        raw.connect(new InetSocketAddress(target, port), connectTimeoutMs);
                        return raw;
                    } catch (IOException e) {
                        lastErr = e;
                        try {
                            raw.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
                throw lastErr != null ? lastErr : new IOException("Failed to connect to pinned address for " + host);
            }

            private void validateHostArg(String reqHost) throws IOException {
                if (isNonPublicHostLiteralOrName(reqHost)) {
                    throw new IOException("Rejected non-public host: " + reqHost);
                }
                String normalized = stripBrackets(reqHost.trim());
                if (!normalized.equalsIgnoreCase(host)) {
                    throw new IOException("Unexpected host mismatch: " + reqHost);
                }
            }

            @Override
            public String[] getDefaultCipherSuites() {
                return delegate.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return delegate.getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket() throws IOException {
                return new Socket() {
                    @Override
                    public void connect(SocketAddress endpoint, int timeout) throws IOException {
                        if (!(endpoint instanceof InetSocketAddress isa)) {
                            throw new IOException("Unsupported socket address");
                        }
                        InetAddress resolved = isa.getAddress();
                        if (resolved != null && isNonPublicAddress(resolved)) {
                            throw new IOException("Rejected non-public target address");
                        }
                        InetAddress target = (resolved != null && pinnedSet.contains(resolved))
                                ? resolved
                                : pinnedAddrs[0];
                        if (isNonPublicAddress(target)) {
                            throw new IOException("Rejected non-public pinned address");
                        }
                        int effTimeout = timeout > 0 ? timeout : connectTimeoutMs;
                        super.connect(new InetSocketAddress(target, isa.getPort()), effTimeout);
                    }
                };
            }

            @Override
            public Socket createSocket(Socket s, String reqHost, int port, boolean autoClose) throws IOException {
                validateHostArg(reqHost);
                if (s != null && s.getInetAddress() != null
                        && (isNonPublicAddress(s.getInetAddress()) || !pinnedSet.contains(s.getInetAddress()))) {
                    throw new IOException("Rejected socket connected to unpinned or non-public address");
                }
                return delegate.createSocket(s, host, port, autoClose);
            }

            @Override
            public Socket createSocket(String reqHost, int port) throws IOException {
                validateHostArg(reqHost);
                Socket raw = connectPinnedRawSocket(null, null, 0, port);
                return delegate.createSocket(raw, host, port, true);
            }

            @Override
            public Socket createSocket(String reqHost, int port, InetAddress localHost, int localPort) throws IOException {
                validateHostArg(reqHost);
                Socket raw = connectPinnedRawSocket(null, localHost, localPort, port);
                return delegate.createSocket(raw, host, port, true);
            }

            @Override
            public Socket createSocket(InetAddress address, int port) throws IOException {
                Socket raw = connectPinnedRawSocket(address, null, 0, port);
                return delegate.createSocket(raw, host, port, true);
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                Socket raw = connectPinnedRawSocket(address, localAddress, localPort, port);
                return delegate.createSocket(raw, host, port, true);
            }
        });
        return conn;
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
            String norm = normalizePublicHttpsUrl(d);
            if (norm != null) all.add(norm);
        }
        if (section != null) {
            for (String c : section.getCustom()) {
                String norm = normalizePublicHttpsUrl(c);
                if (norm != null) all.add(norm);
            }
        }
        List<String> result = new ArrayList<>();
        if (section != null && PeerPulsarConfig.MODE_MANUAL.equals(section.getMode()) && section.getSelected() != null) {
            String sel = normalizePublicHttpsUrl(section.getSelected());
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
        String norm = normalizePublicHttpsUrl(httpsUrl);
        if (norm == null) return -1;
        HttpsURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            conn = openPinnedPublicHttpsConnection(norm + "/", 3000, 3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return (code >= 200 && code < 400) ? Math.max(1L, System.currentTimeMillis() - start) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static long pingIpEchoMs(String httpsUrl) {
        String norm = normalizePublicHttpsUrl(httpsUrl);
        if (norm == null) return -1;
        HttpsURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            conn = openPinnedPublicHttpsConnection(norm, 2500, 2500);
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
