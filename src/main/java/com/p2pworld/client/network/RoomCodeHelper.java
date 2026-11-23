package com.p2pworld.client.network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoomCodeHelper {
    private static final String PREFIX = "P2P-";
    private static final String UDP_PREFIX = "UDP-";
    private static final Pattern IPV4_OR_HOST_PATTERN = Pattern.compile("^([a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?)(?::([0-9]{1,5}))?$");
    private static final Pattern IPV6_BRACKET_PATTERN = Pattern.compile("^\\[([0-9a-fA-F:]+)](?::([0-9]{1,5}))?$");
    private static final Pattern EMBEDDED_UDP_CODE = Pattern.compile("(?i)(?<![A-Za-z0-9_-])UDP-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}(?![A-Za-z0-9_-])");
    private static final Pattern EMBEDDED_HEX_CODE = Pattern.compile("(?i)(?<![A-Za-z0-9_-])P2P-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}(?![A-Za-z0-9_-])");
    private static final Pattern EMBEDDED_B64_CODE = Pattern.compile("(?i)(?<![A-Za-z0-9_-])P2P-[A-Za-z0-9_-]{6,}(?![A-Za-z0-9_-])");

    /**
     * Encodes an IPv4 address (or hostname/IPv6) and port into a clean, shareable Room Code.
     * Example: 85.102.50.21:25565 -> P2P-XXXX-XXXX-XXXX
     */
    public static String encodeRoomCode(String ip, int port) {
        if (ip == null || ip.isBlank() || port < 1 || port > 65535) {
            return "";
        }
        try {
            // Only pack into compact 6-byte XOR hex when input is a literal IPv4 address (avoid blocking DNS & preserve hostnames)
            if (!NetworkDiagnosticsService.isIpv4Literal(ip)) {
                String combined = formatHostPort(ip, port);
                return PREFIX + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(combined.getBytes(StandardCharsets.UTF_8));
            }
            InetAddress address = InetAddress.ofLiteral(ip);
            byte[] ipBytes = address.getAddress();
            if (!(address instanceof Inet4Address) || ipBytes.length != 4) {
                String combined = formatHostPort(ip, port);
                return PREFIX + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(combined.getBytes(StandardCharsets.UTF_8));
            }

            ByteBuffer buffer = ByteBuffer.allocate(6);
            buffer.put(ipBytes);
            buffer.putShort((short) port);
            byte[] data = buffer.array();

            byte magic = 0x5A;
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ magic);
            }

            String hex = bytesToHex(data).toUpperCase();
            if (hex.length() >= 12) {
                return PREFIX + hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
            }
            return PREFIX + hex;
        } catch (Exception e) {
            return formatHostPort(ip, port);
        }
    }

    public static String formatHostPort(String host, int port) {
        if (host == null) {
            return ":" + port;
        }
        return (host.contains(":") && !host.startsWith("["))
                ? ("[" + host + "]:" + port)
                : (host + ":" + port);
    }

    /**
     * Smartly extracts a clean room code if the user pasted an entire Discord invite block.
     */
    public static String extractCleanCode(String rawInput) {
        if (rawInput == null) return "";
        String trimmed = rawInput.trim();
        Matcher udpMatcher = EMBEDDED_UDP_CODE.matcher(trimmed);
        if (udpMatcher.find()) {
            return udpMatcher.group(0).toUpperCase(Locale.ROOT);
        }
        Matcher hexMatcher = EMBEDDED_HEX_CODE.matcher(trimmed);
        while (hexMatcher.find()) {
            String candidate = hexMatcher.group(0).toUpperCase(Locale.ROOT);
            if (decodeSingleToken(candidate) != null) {
                return candidate;
            }
        }
        Matcher b64Matcher = EMBEDDED_B64_CODE.matcher(trimmed);
        while (b64Matcher.find()) {
            String candidate = b64Matcher.group(0);
            if (decodeSingleToken(candidate) != null) {
                return candidate;
            }
        }
        return trimmed.replace("`", "").replace("\"", "").replace("'", "").trim();
    }

    public static ConnectionTarget parseInput(String input) {
        return decodeRoomCode(input);
    }

    /**
     * Decodes a Room Code (UDP-... or P2P-... even embedded in a Discord invite) or parses a raw IP:Port string.
     */
    public static ConnectionTarget decodeRoomCode(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }

        String raw = extractCleanCode(input);
        if (raw.isEmpty()) return null;

        if (raw.toUpperCase().startsWith(UDP_PREFIX)) {
            String cleanUdp = raw.substring(UDP_PREFIX.length()).replace("-", "").trim();
            if (cleanUdp.matches("^[0-9A-Za-z]{8,32}$")) {
                return new ConnectionTarget(raw.toUpperCase(), 0, true);
            }
            return null;
        }

        ConnectionTarget directDecoded = decodeSingleToken(raw);
        if (directDecoded != null) {
            return directDecoded;
        }

        Matcher b64Matcher = EMBEDDED_B64_CODE.matcher(input);
        while (b64Matcher.find()) {
            ConnectionTarget candidate = decodeSingleToken(b64Matcher.group(0));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static ConnectionTarget decodeSingleToken(String raw) {
        if (raw.toUpperCase().startsWith(PREFIX)) {
            String payload = raw.substring(PREFIX.length()).trim();

            // 1. Try 12-char XOR-encoded IPv4:port hex code (may contain visual dashes like XXXX-XXXX-XXXX)
            String hexCandidate = payload.replace("-", "");
            if (hexCandidate.length() == 12 && isHex(hexCandidate)) {
                try {
                    byte[] data = hexToBytes(hexCandidate);
                    byte magic = 0x5A;
                    for (int i = 0; i < data.length; i++) {
                        data[i] = (byte) (data[i] ^ magic);
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    byte[] ipBytes = new byte[4];
                    buffer.get(ipBytes);
                    int port = buffer.getShort() & 0xFFFF;
                    if (port >= 1 && port <= 65535) {
                        InetAddress address = InetAddress.getByAddress(ipBytes);
                        return new ConnectionTarget(address.getHostAddress(), port, false);
                    }
                } catch (Exception ignored) {}
            }

            // 2. Try Base64url-encoded host:port WITHOUT stripping '-' (since '-' is a valid Base64url character!)
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(payload);
                String str = new String(decoded, StandardCharsets.UTF_8);
                return parseDirectHostPort(str);
            } catch (Exception ignored) {}

            // Never fall through and treat an invalid "P2P-..." code as a raw DNS hostname
            return null;
        }

        return parseDirectHostPort(raw);
    }

    private static ConnectionTarget parseDirectHostPort(String input) {
        if (input == null) return null;
        String trimmed = input.trim();

        Matcher v6 = IPV6_BRACKET_PATTERN.matcher(trimmed);
        if (v6.matches()) {
            String host = v6.group(1);
            int port = parsePort(v6.group(2));
            if (port < 1 || port > 65535) return null;
            return new ConnectionTarget(host, port, false);
        }

        Matcher matcher = IPV4_OR_HOST_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            String host = matcher.group(1);
            if (host.contains("..")) return null;
            int port = parsePort(matcher.group(2));
            if (port < 1 || port > 65535) return null;
            return new ConnectionTarget(host, port, false);
        }
        return null;
    }

    private static int parsePort(String portStr) {
        if (portStr == null || portStr.isEmpty()) {
            return 25565;
        }
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String generateDiscordInvite(String roomCode, String hostIp, int port, boolean offlineMode, String gameMode) {
        String safeMode = gameMode != null ? gameMode.toUpperCase(Locale.ROOT) : "SURVIVAL";
        boolean isUdp = roomCode != null && roomCode.toUpperCase(Locale.ROOT).startsWith(UDP_PREFIX);
        String endpointLine = isUdp
                ? "⚡ **Connection:** Direct UDP P2P (paste Room Code in 'Join P2P')\n"
                : "🌐 **Direct Address:** `" + formatHostPort(hostIp, port) + "`\n";
        return "🎮 **Join my Minecraft 26.3 P2P World (PeerPulsar)!**\n" +
                "🔑 **Room Code:** `" + roomCode + "`\n" +
                endpointLine +
                "⚡ **Offline LAN Mode:** " + (offlineMode ? "Enabled" : "Disabled (Online Auth)") + "\n" +
                "⚔️ **Game Mode:** " + safeMode + "\n" +
                "👉 *Paste this invite into the 'Join P2P' screen in PeerPulsar!*";
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static boolean isHex(String s) {
        return s.matches("^[0-9a-fA-F]+$");
    }

    public record ConnectionTarget(String host, int port, boolean isUdpHolePunch) {
        @Override
        public String toString() {
            return isUdpHolePunch ? host : formatHostPort(host, port);
        }
    }
}
