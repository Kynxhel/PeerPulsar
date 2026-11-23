package com.p2pworld.client.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class RoomCodeHelperTest {

    @Test
    void ipv4EncodeAndDecodeRoundTrip() {
        String code = RoomCodeHelper.encodeRoomCode("85.102.50.21", 25565);
        assertTrue(code.matches("^P2P-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$"),
                "Expected compact XOR hex room code, got: " + code);

        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(code);
        assertNotNull(target);
        assertEquals("85.102.50.21", target.host());
        assertEquals(25565, target.port());
        assertFalse(target.isUdpHolePunch());

        // Case-insensitive and dashless decoding
        RoomCodeHelper.ConnectionTarget lower = RoomCodeHelper.decodeRoomCode(code.toLowerCase());
        assertEquals(target, lower);
        RoomCodeHelper.ConnectionTarget dashless = RoomCodeHelper.decodeRoomCode("P2P-" + code.substring(4).replace("-", ""));
        assertEquals(target, dashless);
    }

    @Test
    void ipv4HighAndBoundaryPortsRoundTrip() {
        for (int port : new int[]{1, 25565, 41234, 65535}) {
            String code = RoomCodeHelper.encodeRoomCode("192.168.1.42", port);
            RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(code);
            assertNotNull(target, "Failed to decode port " + port);
            assertEquals("192.168.1.42", target.host());
            assertEquals(port, target.port());
            assertFalse(target.isUdpHolePunch());
        }
    }

    @Test
    void ipv6Base64EncodeAndDecodeRoundTrip() {
        String code = RoomCodeHelper.encodeRoomCode("2001:db8::1", 25565);
        assertTrue(code.startsWith("P2P-"), "Expected P2P- prefix, got: " + code);

        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(code);
        assertNotNull(target);
        assertEquals("2001:db8::1", target.host());
        assertEquals(25565, target.port());
        assertFalse(target.isUdpHolePunch());

        String loopbackCode = RoomCodeHelper.encodeRoomCode("::1", 45678);
        RoomCodeHelper.ConnectionTarget loopbackTarget = RoomCodeHelper.decodeRoomCode(loopbackCode);
        assertNotNull(loopbackTarget);
        assertEquals("::1", loopbackTarget.host());
        assertEquals(45678, loopbackTarget.port());
        assertFalse(loopbackTarget.isUdpHolePunch());
    }

    @Test
    void base64HostnameDecodeRoundTrip() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("bore.pub:41234".getBytes(StandardCharsets.UTF_8));
        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode("P2P-" + payload);
        assertNotNull(target);
        assertEquals("bore.pub", target.host());
        assertEquals(41234, target.port());
        assertFalse(target.isUdpHolePunch());
    }

    @Test
    void hostnameEncodeAndDecodePreservesHostnameWithoutDns() {
        String code = RoomCodeHelper.encodeRoomCode("bore.pub", 41234);
        assertTrue(code.startsWith("P2P-"), "Expected P2P- prefix, got: " + code);
        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(code);
        assertNotNull(target);
        assertEquals("bore.pub", target.host());
        assertEquals(41234, target.port());
        assertFalse(target.isUdpHolePunch());

        // Non-resolvable hostname must also encode cleanly without blocking on DNS
        String offlineHostCode = RoomCodeHelper.encodeRoomCode("nonexistent.invalid.example", 25566);
        RoomCodeHelper.ConnectionTarget offlineTarget = RoomCodeHelper.decodeRoomCode(offlineHostCode);
        assertNotNull(offlineTarget);
        assertEquals("nonexistent.invalid.example", offlineTarget.host());
        assertEquals(25566, offlineTarget.port());
        assertFalse(offlineTarget.isUdpHolePunch());
    }

    @Test
    void discordInviteExtractionAndDecode() {
        String ipv4Code = RoomCodeHelper.encodeRoomCode("85.102.50.21", 25565);
        String ipv4Invite = RoomCodeHelper.generateDiscordInvite(ipv4Code, "85.102.50.21", 25565, true, "survival");
        assertEquals(ipv4Code, RoomCodeHelper.extractCleanCode(ipv4Invite));
        RoomCodeHelper.ConnectionTarget ipv4Target = RoomCodeHelper.decodeRoomCode(ipv4Invite);
        assertNotNull(ipv4Target);
        assertEquals("85.102.50.21", ipv4Target.host());
        assertEquals(25565, ipv4Target.port());
        assertFalse(ipv4Target.isUdpHolePunch());

        String udpCode = "UDP-ABCD-1234-EFGH";
        String udpInvite = RoomCodeHelper.generateDiscordInvite(udpCode, "1.2.3.4", 50000, false, "creative");
        assertEquals(udpCode, RoomCodeHelper.extractCleanCode(udpInvite));
        RoomCodeHelper.ConnectionTarget udpTarget = RoomCodeHelper.decodeRoomCode(udpInvite);
        assertNotNull(udpTarget);
        assertEquals(udpCode, udpTarget.host());
        assertEquals(0, udpTarget.port());
        assertTrue(udpTarget.isUdpHolePunch());

        String ipv6Code = RoomCodeHelper.encodeRoomCode("2001:db8::1", 25566);
        String ipv6Invite = RoomCodeHelper.generateDiscordInvite(ipv6Code, "2001:db8::1", 25566, true, "adventure");
        assertEquals(ipv6Code, RoomCodeHelper.extractCleanCode(ipv6Invite));
        RoomCodeHelper.ConnectionTarget ipv6Target = RoomCodeHelper.decodeRoomCode(ipv6Invite);
        assertNotNull(ipv6Target);
        assertEquals("2001:db8::1", ipv6Target.host());
        assertEquals(25566, ipv6Target.port());
        assertFalse(ipv6Target.isUdpHolePunch());
    }

    @Test
    void udpRoomCodeParsing() {
        RoomCodeHelper.ConnectionTarget upper = RoomCodeHelper.decodeRoomCode("UDP-ABCD-1234-EFGH");
        assertNotNull(upper);
        assertEquals("UDP-ABCD-1234-EFGH", upper.host());
        assertEquals(0, upper.port());
        assertTrue(upper.isUdpHolePunch());

        RoomCodeHelper.ConnectionTarget lower = RoomCodeHelper.decodeRoomCode("udp-ab12-cd34-ef56");
        assertNotNull(lower);
        assertEquals("UDP-AB12-CD34-EF56", lower.host());
        assertTrue(lower.isUdpHolePunch());

        assertNull(RoomCodeHelper.decodeRoomCode("UDP-1234"));
        assertNull(RoomCodeHelper.decodeRoomCode("UDP-"));
    }

    @Test
    void directHostPortAndPortBounds() {
        RoomCodeHelper.ConnectionTarget v4 = RoomCodeHelper.parseInput("127.0.0.1:25565");
        assertEquals(new RoomCodeHelper.ConnectionTarget("127.0.0.1", 25565, false), v4);

        RoomCodeHelper.ConnectionTarget defaultPort = RoomCodeHelper.parseInput("127.0.0.1");
        assertEquals(new RoomCodeHelper.ConnectionTarget("127.0.0.1", 25565, false), defaultPort);

        RoomCodeHelper.ConnectionTarget v6 = RoomCodeHelper.parseInput("[2001:db8::1]:25566");
        assertEquals(new RoomCodeHelper.ConnectionTarget("2001:db8::1", 25566, false), v6);

        RoomCodeHelper.ConnectionTarget v6Default = RoomCodeHelper.parseInput("[::1]");
        assertEquals(new RoomCodeHelper.ConnectionTarget("::1", 25565, false), v6Default);

        assertEquals(1, RoomCodeHelper.parseInput("127.0.0.1:1").port());
        assertEquals(65535, RoomCodeHelper.parseInput("127.0.0.1:65535").port());

        assertNull(RoomCodeHelper.parseInput("127.0.0.1:0"));
        assertNull(RoomCodeHelper.parseInput("127.0.0.1:65536"));
        assertNull(RoomCodeHelper.parseInput("127.0.0.1:99999"));
        assertNull(RoomCodeHelper.parseInput("[::1]:0"));
        assertNull(RoomCodeHelper.parseInput("[::1]:65536"));
    }

    @Test
    void invalidAndMalformedInputsReturnNull() {
        assertNull(RoomCodeHelper.decodeRoomCode(null));
        assertNull(RoomCodeHelper.decodeRoomCode(""));
        assertNull(RoomCodeHelper.decodeRoomCode("   "));
        assertNull(RoomCodeHelper.decodeRoomCode("not a valid host!"));
        assertNull(RoomCodeHelper.decodeRoomCode("bad..host:25565"));
        assertNull(RoomCodeHelper.decodeRoomCode("P2P-"));
        assertNull(RoomCodeHelper.decodeRoomCode("P2P-!!!"));
        // XOR 0x5A with port bytes 0x5A5A decodes to port 0, which must be rejected
        assertNull(RoomCodeHelper.decodeRoomCode("P2P-5A5A-5A5A-5A5A"));
        assertEquals("", RoomCodeHelper.extractCleanCode(null));
    }

    @Test
    void formatHostPortBracketsIpv6Only() {
        assertEquals("127.0.0.1:25565", RoomCodeHelper.formatHostPort("127.0.0.1", 25565));
        assertEquals("bore.pub:12345", RoomCodeHelper.formatHostPort("bore.pub", 12345));
        assertEquals("[2001:db8::1]:25565", RoomCodeHelper.formatHostPort("2001:db8::1", 25565));
        assertEquals("[2001:db8::1]:25565", RoomCodeHelper.formatHostPort("[2001:db8::1]", 25565));
    }

    @Test
    void rejectsOutOfRangePortsAndInvalidUdpCharacters() {
        assertEquals("", RoomCodeHelper.encodeRoomCode("85.102.50.21", 0));
        assertEquals("", RoomCodeHelper.encodeRoomCode("85.102.50.21", -1));
        assertEquals("", RoomCodeHelper.encodeRoomCode("85.102.50.21", 65536));
        assertNull(RoomCodeHelper.decodeRoomCode("UDP-my room code"));
        assertNull(RoomCodeHelper.decodeRoomCode("UDP-../other"));
    }

    @Test
    void decodesRealBase64CodeEvenAfterDecoyPrefixInMessage() {
        String realCode = RoomCodeHelper.encodeRoomCode("bore.pub", 41234);
        String msg = "Join my P2P-server at " + realCode + "!";
        assertEquals(realCode, RoomCodeHelper.extractCleanCode(msg));
        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(msg);
        assertNotNull(target);
        assertEquals("bore.pub", target.host());
        assertEquals(41234, target.port());
    }

    @Test
    void extractCleanCodeSkipsInvalidHexDecoy() {
        String realCode = RoomCodeHelper.encodeRoomCode("85.102.50.21", 25565);
        String msg = "Not P2P-5A5A-5A5A-5A5A, real code is " + realCode;
        assertEquals(realCode, RoomCodeHelper.extractCleanCode(msg));
    }

    @Test
    void udpDiscordInviteDoesNotLeakRawTcpAddress() {
        String udpCode = "UDP-ABCD-1234-EFGH";
        String invite = RoomCodeHelper.generateDiscordInvite(udpCode, "85.102.50.21", 51234, true, "survival");
        assertTrue(invite.contains(udpCode));
        assertFalse(invite.contains("85.102.50.21"));
        assertFalse(invite.contains("Direct Address"));
        RoomCodeHelper.ConnectionTarget decoded = RoomCodeHelper.decodeRoomCode(invite);
        assertNotNull(decoded);
        assertTrue(decoded.isUdpHolePunch());
        assertEquals(udpCode, decoded.host());
    }

    @Test
    void invalidP2PPrefixedCodesNeverFallThroughAsHostnames() {
        // Length 4n+1 payloads cause Base64.getUrlDecoder().decode() to throw IllegalArgumentException;
        // they must return null rather than falling through to parseDirectHostPort().
        assertNull(RoomCodeHelper.decodeRoomCode("P2P-ABCD1"));
        assertNull(RoomCodeHelper.decodeRoomCode("P2P-world1234"));

        String realCode = RoomCodeHelper.encodeRoomCode("bore.pub", 41234);
        String msg = "Join my P2P-world1234 server using " + realCode;
        assertEquals(realCode, RoomCodeHelper.extractCleanCode(msg));
        RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.decodeRoomCode(msg);
        assertNotNull(target);
        assertEquals("bore.pub", target.host());
        assertEquals(41234, target.port());
    }
}
