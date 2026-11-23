package com.p2pworld.client.network;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoomCodeHelper {
    private static final String PREFIX = "P2P-";
    private static final String UDP_PREFIX = "UDP-";
    private static final Pattern IP_PORT_PATTERN = Pattern.compile("^([a-zA-Z0-9.-]+)(?::([0-9]{1,5}))?$");
    private static final Pattern EMBEDDED_UDP_CODE = Pattern.compile("(?i)UDP-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}");
    private static final Pattern EMBEDDED_HEX_CODE = Pattern.compile("(?i)P2P-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}");
    private static final Pattern EMBEDDED_B64_CODE = Pattern.compile("(?i)P2P-[A-Za-z0-9_-]{6,}");

    /**
     * Encodes an IPv4 address and port into a clean, shareable Room Code.
     * Example: 85.102.50.21:25565 -> P2P-XXXX-XXXX-XXXX
     */
    public static String encodeRoomCode(String ip, int port) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] ipBytes = address.getAddress();
            if (ipBytes.length != 4) {
                String combined = ip + ":" + port;
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
            return ip + ":" + port;
        }
    }

    /**
     * Smartly extracts a clean room code if the user pasted an entire Discord invite block.
     */
    public static String extractCleanCode(String rawInput) {
        if (rawInput == null) return "";
        String trimmed = rawInput.trim();
        Matcher udpMatcher = EMBEDDED_UDP_CODE.matcher(trimmed);
        if (udpMatcher.find()) {
            return udpMatcher.group(0).toUpperCase();
        }
        Matcher hexMatcher = EMBEDDED_HEX_CODE.matcher(trimmed);
        if (hexMatcher.find()) {
            return hexMatcher.group(0).toUpperCase();
        }
        Matcher b64Matcher = EMBEDDED_B64_CODE.matcher(trimmed);
        if (b64Matcher.find()) {
            return b64Matcher.group(0);
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
            if (cleanUdp.length() >= 8) {
                return new ConnectionTarget(raw.toUpperCase(), 0, true);
            }
            return null;
        }

        if (raw.toUpperCase().startsWith(PREFIX)) {
            String cleanCode = raw.substring(PREFIX.length()).replace("-", "").trim();
            if (cleanCode.length() == 12 && isHex(cleanCode)) {
                try {
                    byte[] data = hexToBytes(cleanCode);
                    byte magic = 0x5A;
                    for (int i = 0; i < data.length; i++) {
                        data[i] = (byte) (data[i] ^ magic);
                    }
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    byte[] ipBytes = new byte[4];
                    buffer.get(ipBytes);
                    int port = buffer.getShort() & 0xFFFF;
                    if (port < 1 || port > 65535) return null;
                    InetAddress address = InetAddress.getByAddress(ipBytes);
                    return new ConnectionTarget(address.getHostAddress(), port, false);
                } catch (Exception ignored) {}
            }

            try {
                byte[] decoded = Base64.getUrlDecoder().decode(cleanCode);
                String str = new String(decoded, StandardCharsets.UTF_8);
                return parseDirectHostPort(str);
            } catch (Exception ignored) {}
        }

        return parseDirectHostPort(raw);
    }

    private static ConnectionTarget parseDirectHostPort(String input) {
        Matcher matcher = IP_PORT_PATTERN.matcher(input);
        if (matcher.matches()) {
            String host = matcher.group(1);
            String portStr = matcher.group(2);
            int port = 25565;
            if (portStr != null && !portStr.isEmpty()) {
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (port < 1 || port > 65535) return null;
            return new ConnectionTarget(host, port, false);
        }
        return null;
    }

    public static String generateDiscordInvite(String roomCode, String hostIp, int port, boolean offlineMode, String gameMode) {
        return "🎮 **Join my Minecraft 26.3 P2P World!**\n" +
                "🔑 **Room Code:** `" + roomCode + "`\n" +
                "🌐 **Direct Address:** `" + hostIp + ":" + port + "`\n" +
                "⚡ **Offline LAN Mode:** " + (offlineMode ? "Enabled" : "Disabled (Online Auth)") + "\n" +
                "⚔️ **Game Mode:** " + gameMode.toUpperCase() + "\n" +
                "👉 *Paste this invite into the 'Join P2P' screen in Fabric P2P World!*";
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
            return isUdpHolePunch ? host : (host + ":" + port);
        }
    }
}
