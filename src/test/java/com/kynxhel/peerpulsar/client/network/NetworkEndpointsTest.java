package com.kynxhel.peerpulsar.client.network;

import com.kynxhel.peerpulsar.config.PeerPulsarConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    @Test
    void upnpLocalGatewayUrlValidationAllowsMatchingLanAndRejectsNonLocalOrMismatch() throws Exception {
        InetAddress gw1 = InetAddress.ofLiteral("192.168.1.1");
        assertTrue(UPnPService.isAllowedLocalGatewayUrl("http://192.168.1.1:5000/rootDesc.xml", gw1));
        assertTrue(UPnPService.isAllowedLocalGatewayUrl("https://192.168.1.1:5443/ctl/IPConn", gw1));
        assertTrue(UPnPService.isAllowedLocalGatewayUrl("http://192.168.1.1:5000/rootDesc.xml", null));

        assertFalse(UPnPService.isAllowedLocalGatewayUrl(null, gw1));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("   ", gw1));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://127.0.0.1:5000/x", null));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://127.0.0.1:5000/x", InetAddress.ofLiteral("127.0.0.1")));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://8.8.8.8/x", null));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://8.8.8.8/x", InetAddress.ofLiteral("8.8.8.8")));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("file:///etc/passwd", gw1));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://192.168.1.2:5000/x", gw1));
        assertFalse(UPnPService.isAllowedLocalGatewayUrl("http://user@192.168.1.1:5000/x", gw1));
    }

    @Test
    void openPinnedPublicHttpsConnectionRejectsNonPublicAndNonHttps() {
        assertThrows(IOException.class, () -> NetworkEndpoints.openPinnedPublicHttpsConnection("https://127.0.0.1/test", 1000, 1000));
        assertThrows(IOException.class, () -> NetworkEndpoints.openPinnedPublicHttpsConnection("https://169.254.169.254/latest", 1000, 1000));
        assertThrows(IOException.class, () -> NetworkEndpoints.openPinnedPublicHttpsConnection("http://ntfy.sh/test", 1000, 1000));
        assertThrows(IOException.class, () -> NetworkEndpoints.openPinnedPublicHttpsConnection("https://localhost/test", 1000, 1000));
    }

    @Test
    void publicHostPortValidationAndPinnedResolutionRejectNonPublicTargets(@TempDir Path tempDir) {
        assertTrue(NetworkEndpoints.isValidPublicHostPort("bore.pub:7835"));
        assertTrue(NetworkEndpoints.isValidPublicHostPort("relay.example.com:443"));
        assertTrue(NetworkEndpoints.isValidPublicHostPort("[2001:db8::1]:7835"));

        assertFalse(NetworkEndpoints.isValidPublicHostPort("localhost:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("127.0.0.1:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("192.168.1.1:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("10.0.0.5:3478"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("169.254.169.254:80"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("100.64.0.1:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("[::1]:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("[fc00::1]:7835"));
        assertFalse(NetworkEndpoints.isValidPublicHostPort("router.lan:7835"));

        PeerPulsarConfig cfg = PeerPulsarConfig.loadFromFile(tempDir.resolve("cfg-ssrf.json"));
        cfg.getRelaySection().addCustom("127.0.0.1:7835");
        cfg.getRelaySection().addCustom("192.168.1.50:7835");
        cfg.getRelaySection().setMode(PeerPulsarConfig.MODE_MANUAL);
        cfg.getRelaySection().setSelected("127.0.0.1:7835");
        List<NetworkEndpoints.HostPort> relays = NetworkEndpoints.getOrderedRelays(cfg);
        assertEquals(1, relays.size());
        assertEquals("bore.pub:7835", relays.get(0).formatted());

        cfg.getStunSection().addCustom("10.0.0.1:3478");
        assertTrue(NetworkEndpoints.getOrderedStun(cfg).stream().noneMatch(hp -> hp.host().equals("10.0.0.1")));

        assertEquals(-1L, NetworkEndpoints.pingRelayMs("127.0.0.1:7835"));
        assertEquals(-1L, NetworkEndpoints.pingStunMs("192.168.1.1:3478"));

        assertThrows(IOException.class, () -> NetworkEndpoints.resolvePinnedPublicAddress("127.0.0.1", false));
        assertThrows(IOException.class, () -> NetworkEndpoints.resolvePinnedPublicAddress("localhost", true));
        assertThrows(IOException.class, () -> NetworkEndpoints.resolvePinnedPublicAddress("192.168.1.1", false));
        assertThrows(IOException.class, () -> NetworkEndpoints.resolvePinnedPublicAddress("169.254.169.254", true));
    }

    @Test
    void hostDiagnosticsConsentAndUdpJoinConsentAreIndependent(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("consent-cfg.json");
        PeerPulsarConfig cfg = PeerPulsarConfig.loadFromFile(cfgFile);
        assertFalse(cfg.hasNetworkTestConsent());
        assertFalse(cfg.hasUdpJoinConsent());

        cfg.setUdpJoinConsent(true);
        assertTrue(cfg.hasUdpJoinConsent());
        assertFalse(cfg.hasNetworkTestConsent());

        PeerPulsarConfig reloaded = PeerPulsarConfig.loadFromFile(cfgFile);
        assertTrue(reloaded.hasUdpJoinConsent());
        assertFalse(reloaded.hasNetworkTestConsent());

        reloaded.setNetworkTestConsent(true);
        PeerPulsarConfig reloadedBoth = PeerPulsarConfig.loadFromFile(cfgFile);
        assertTrue(reloadedBoth.hasUdpJoinConsent());
        assertTrue(reloadedBoth.hasNetworkTestConsent());

        Files.writeString(cfgFile, "{corrupt", StandardCharsets.UTF_8);
        PeerPulsarConfig corruptReload = PeerPulsarConfig.loadFromFile(cfgFile);
        assertFalse(corruptReload.hasUdpJoinConsent());
        assertFalse(corruptReload.hasNetworkTestConsent());
    }
}
