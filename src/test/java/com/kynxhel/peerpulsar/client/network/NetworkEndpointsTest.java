package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkEndpointsTest {

    @Test
    void hostPortValidationAcceptsValidAndRejectsInvalid() {
        assertTrue(NetworkEndpoints.isValidHostPort("bore.pub:7835"));
        assertTrue(NetworkEndpoints.isValidHostPort("relay.example.com:443"));
        assertTrue(NetworkEndpoints.isValidHostPort("[2001:db8::1]:7835"));

        assertFalse(NetworkEndpoints.isValidHostPort(null));
        assertFalse(NetworkEndpoints.isValidHostPort(""));
        assertFalse(NetworkEndpoints.isValidHostPort("bore.pub"));
        assertFalse(NetworkEndpoints.isValidHostPort("bore.pub:0"));
        assertFalse(NetworkEndpoints.isValidHostPort("bore.pub:65536"));
        assertFalse(NetworkEndpoints.isValidHostPort("bad..host:7835"));
        assertFalse(NetworkEndpoints.isValidHostPort("https://bore.pub:7835"));
    }

    @Test
    void httpsUrlValidationEnforcesHttpsAndNormalizes() {
        assertEquals("https://ntfy.sh", NetworkEndpoints.normalizeHttpsUrl("https://ntfy.sh/"));
        assertEquals("https://ntfy.example.com:8443", NetworkEndpoints.normalizeHttpsUrl("  HTTPS://Ntfy.Example.com:8443/ "));

        assertFalse(NetworkEndpoints.isValidHttpsUrl("http://ntfy.sh"));
        assertFalse(NetworkEndpoints.isValidHttpsUrl("ntfy.sh"));
        assertFalse(NetworkEndpoints.isValidHttpsUrl("https://ntfy.sh/topic"));
        assertFalse(NetworkEndpoints.isValidHttpsUrl("https://user@ntfy.sh"));
        assertFalse(NetworkEndpoints.isValidHttpsUrl("https://ntfy.sh?a=1"));
        assertFalse(NetworkEndpoints.isValidHttpsUrl("https://bad host"));
    }

    @Test
    void publicHttpsUrlRejectsLocalPrivateCgnatAndInternalHosts() throws Exception {
        assertEquals("https://ntfy.sh", NetworkEndpoints.normalizePublicHttpsUrl("https://ntfy.sh"));
        assertEquals("https://ntfy.example.com:8443", NetworkEndpoints.normalizePublicHttpsUrl("https://ntfy.example.com:8443/"));
        assertEquals("https://93.184.216.34", NetworkEndpoints.normalizePublicHttpsUrl("https://93.184.216.34"));
        assertEquals("https://[2606:4700:4700::1111]", NetworkEndpoints.normalizePublicHttpsUrl("https://[2606:4700:4700::1111]"));

        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://localhost"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://LOCALHOST."));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://sub.localhost"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://127.0.0.1"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://169.254.169.254"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://192.168.1.1"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://10.0.0.5"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://172.16.0.1"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://100.64.0.1"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://[::1]"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://[fc00::1]"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://[fe80::1]"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://host.local"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://service.internal"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://router.lan"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://nas.home.arpa"));
        assertNull(NetworkEndpoints.normalizePublicHttpsUrl("https://intranet"));

        assertTrue(NetworkEndpoints.isNonPublicHostLiteralOrName(null));
        assertTrue(NetworkEndpoints.isNonPublicHostLiteralOrName(" "));
        assertTrue(NetworkEndpoints.isNonPublicAddress(null));
        assertTrue(NetworkEndpoints.isNonPublicAddress(InetAddress.ofLiteral("100.127.255.254")));
        assertFalse(NetworkEndpoints.isNonPublicAddress(InetAddress.ofLiteral("100.128.0.1")));
        assertTrue(NetworkEndpoints.isNonPublicAddress(InetAddress.ofLiteral("fd12:3456:789a::1")));
        assertFalse(NetworkEndpoints.isNonPublicAddress(InetAddress.ofLiteral("8.8.8.8")));
    }

    @Test
    void corruptConfigFallsBackToDefaultsSafely(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("peerpulsar.json");
        Files.writeString(cfgFile, "{not valid json!!!", StandardCharsets.UTF_8);

        PeerPulsarConfig cfg = PeerPulsarConfig.loadFromFile(cfgFile);
        assertNotNull(cfg);
        assertFalse(cfg.hasNetworkTestConsent());
        assertFalse(cfg.isUseDefaultPort());
        assertFalse(cfg.isJoinApprovalEnabled());
        assertFalse(cfg.isRequireRoomKeyOnLan());
        assertFalse(cfg.isGiveJoinersCommands());
        assertEquals(PeerPulsarConfig.MODE_AUTO, cfg.getRelaySection().getMode());
        assertEquals("bore.pub:7835", NetworkEndpoints.getOrderedRelays(cfg).get(0).formatted());
        assertEquals("https://ntfy.sh", NetworkEndpoints.getOrderedSignaling(cfg).get(0));
    }

    @Test
    void dualStunPairAlwaysSelectsTwoDistinctProviders(@TempDir Path tempDir) {
        PeerPulsarConfig cfg = PeerPulsarConfig.loadFromFile(tempDir.resolve("cfg.json"));
        List<NetworkEndpoints.HostPort> pair = NetworkEndpoints.getDualStunPair(cfg);
        assertEquals(2, pair.size());
        String p0 = NetworkEndpoints.providerKey(pair.get(0).host());
        String p1 = NetworkEndpoints.providerKey(pair.get(1).host());
        assertNotEquals(p0, p1, "Dual-STUN must pick two different STUN providers");
    }
}
