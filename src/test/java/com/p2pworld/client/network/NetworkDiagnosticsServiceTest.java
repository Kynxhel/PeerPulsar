package com.p2pworld.client.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDiagnosticsServiceTest {

    @Test
    void detectsCgnatRange() {
        // RFC 6598: 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.64.0.0"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.64.0.1"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.100.50.25"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.127.255.254"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.127.255.255"));

        // Immediate boundaries outside CGNAT
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.63.0.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.63.255.255"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.128.0.0"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.128.0.1"));
    }

    @Test
    void detectsRfc1918PrivateRanges() {
        // 10.0.0.0/8
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("10.0.0.1"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("10.255.255.254"));

        // 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("172.16.0.1"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("172.24.10.5"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("172.31.255.254"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("172.15.255.255"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("172.32.0.1"));

        // 192.168.0.0/16
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.168.0.1"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.168.1.100"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.168.255.254"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.167.1.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.169.1.1"));
    }

    @Test
    void detectsLoopbackAndUnspecified() {
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("127.0.0.1"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("127.255.255.254"));
        assertTrue(NetworkDiagnosticsService.isCgnatOrPrivateIp("0.0.0.0"));
    }

    @Test
    void allowsPublicIps() {
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("8.8.8.8"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("1.1.1.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("85.102.50.21"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.63.0.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("100.128.0.1"));
    }

    @Test
    void handlesNullAndInvalidStringsSafely() {
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp(null));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp(""));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("   "));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("not-an-ip"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.168.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("192.168.1.1.1"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("abc.def.ghi.jkl"));
        assertFalse(NetworkDiagnosticsService.isCgnatOrPrivateIp("2001:db8::1"));
    }
}
