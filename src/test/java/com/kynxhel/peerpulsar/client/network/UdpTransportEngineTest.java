package com.kynxhel.peerpulsar.client.network;

import org.junit.jupiter.api.Test;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.Statistics;
import tech.kwik.core.log.NullLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UdpTransportEngineTest {

    @Test
    void ephemeralTlsCertGeneratesValidX509AndPinnedTrustManagerVerifies() throws Exception {
        EphemeralTlsCert cert1 = EphemeralTlsCert.generate();
        EphemeralTlsCert cert2 = EphemeralTlsCert.generate();

        assertNotNull(cert1.keyStore());
        assertNotNull(cert1.certificate());
        assertTrue(cert1.sha256Hex().matches("^[0-9a-f]{64}$"));
        assertNotEquals(cert1.sha256Hex(), cert2.sha256Hex());

        X509TrustManager tm = EphemeralTlsCert.pinnedTrustManager(cert1.sha256Hex());
        assertDoesNotThrow(() -> tm.checkServerTrusted(new X509Certificate[]{cert1.certificate()}, "ECDHE_ECDSA"));
        assertThrows(CertificateException.class,
                () -> tm.checkServerTrusted(new X509Certificate[]{cert2.certificate()}, "ECDHE_ECDSA"));
        assertThrows(IllegalArgumentException.class, () -> EphemeralTlsCert.pinnedTrustManager("not-hex"));
    }

    @Test
    void autoEngineSelectionThresholdsMatchDesign() {
        // Clean low ping fiber -> RUDP
        assertEquals(UdpTransportMode.RUDP, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 4, 30.0, 4.0, 0.0), true));

        // Moderate jitter or mild loss -> KCP
        assertEquals(UdpTransportMode.KCP, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 4, 42.0, 19.0, 0.0), true));
        assertEquals(UdpTransportMode.KCP, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 4, 78.0, 6.0, 2.0), true));

        // High ping or heavy loss -> QUIC (when host supports it)
        assertEquals(UdpTransportMode.QUIC, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 4, 132.0, 5.0, 0.0), true));
        assertEquals(UdpTransportMode.QUIC, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 3, 55.0, 8.0, 6.5), true));

        // Without QUIC support on host, degrades cleanly to KCP
        assertEquals(UdpTransportMode.KCP, TransportSwitchAdvisor.chooseInitialEngine(
                new TransportSwitchAdvisor.ProbeResult(4, 4, 145.0, 10.0, 0.0), false));
    }

    @Test
    void switchAdvisorHysteresisAndCooldownPreventFlapping() {
        TransportSwitchAdvisor advisor = new TransportSwitchAdvisor();
        long t0 = 1_000_000L;

        // Short 10s spike to 145ms should NOT trigger switch
        assertTrue(advisor.evaluateSample(t0, UdpTransportMode.RUDP, true, 145.0, 12.0, 0.0).isEmpty());
        assertTrue(advisor.evaluateSample(t0 + 10_000L, UdpTransportMode.RUDP, true, 145.0, 12.0, 0.0).isEmpty());

        // Drop back to clean link resets candidate timer
        assertTrue(advisor.evaluateSample(t0 + 12_000L, UdpTransportMode.RUDP, true, 32.0, 3.0, 0.0).isEmpty());

        // Sustained >=26s high ping triggers QUIC recommendation
        long tDegrade = t0 + 20_000L;
        assertTrue(advisor.evaluateSample(tDegrade, UdpTransportMode.RUDP, true, 138.0, 10.0, 0.0).isEmpty());
        assertTrue(advisor.evaluateSample(tDegrade + 20_000L, UdpTransportMode.RUDP, true, 142.0, 10.0, 0.0).isEmpty());
        Optional<TransportSwitchAdvisor.SwitchRecommendation> rec1 =
                advisor.evaluateSample(tDegrade + 26_500L, UdpTransportMode.RUDP, true, 140.0, 10.0, 0.0);
        assertTrue(rec1.isPresent());
        assertEquals(UdpTransportMode.QUIC, rec1.get().targetEngine());

        // Within 5-minute cooldown, recommendations remain suppressed
        long tCool = tDegrade + 30_000L;
        assertTrue(advisor.evaluateSample(tCool, UdpTransportMode.RUDP, true, 150.0, 10.0, 0.0).isEmpty());
        assertTrue(advisor.evaluateSample(tCool + 40_000L, UdpTransportMode.RUDP, true, 150.0, 10.0, 0.0).isEmpty());

        // After cooldown expires on QUIC and link becomes ultra clean for >=30s -> recommends RUDP
        long tAfter = tDegrade + 26_500L + TransportSwitchAdvisor.COOLDOWN_MS + 1000L;
        assertTrue(advisor.evaluateSample(tAfter, UdpTransportMode.QUIC, true, 34.0, 3.0, 0.0).isEmpty());
        Optional<TransportSwitchAdvisor.SwitchRecommendation> rec2 =
                advisor.evaluateSample(tAfter + 31_000L, UdpTransportMode.QUIC, true, 34.0, 3.0, 0.0);
        assertTrue(rec2.isPresent());
        assertEquals(UdpTransportMode.RUDP, rec2.get().targetEngine());
    }

    @Test
    void kcpSackMaskEncodesOutOfOrderWindowAndRecoversDroppedPackets() throws Exception {
        long mask = UdpHolePunchService.computeSackMask(10, Set.of(10, 11, 13, 15, 74));
        // Bit 0 -> seq 11, Bit 2 -> seq 13, Bit 4 -> seq 15, Bit 63 -> seq 74 (10 + 1 + 63)
        long expected = (1L << 0) | (1L << 2) | (1L << 4) | (1L << 63);
        assertEquals(expected, mask);

        byte[] payload = new byte[16 * 1024];
        new SecureRandom().nextBytes(payload);

        byte[] rudpOut = UdpHolePunchService.loopbackSessionTransferForTest(UdpTransportMode.RUDP, payload, false);
        assertArrayEquals(payload, rudpOut);

        byte[] kcpOut = UdpHolePunchService.loopbackSessionTransferForTest(UdpTransportMode.KCP, payload, true);
        assertArrayEquals(payload, kcpOut);
    }

    @Test
    void demuxSocketAndKwikQuicLoopbackTransferWorks() throws Exception {
        EphemeralTlsCert cert = EphemeralTlsCert.generate();
        byte[] payload = new byte[64 * 1024];
        new SecureRandom().nextBytes(payload);

        try (DatagramSocket serverRaw = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket clientRaw = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DemuxDatagramSocket serverDemux = new DemuxDatagramSocket(serverRaw);
             DemuxDatagramSocket clientDemux = new DemuxDatagramSocket(clientRaw)) {

            Thread serverPump = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (!serverRaw.isClosed() && !serverDemux.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        serverRaw.receive(p);
                        serverDemux.enqueueIncoming(buf, 0, p.getLength(), p.getSocketAddress());
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }, "Test-ServerDemux");
            serverPump.setDaemon(true);
            serverPump.start();

            Thread clientPump = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (!clientRaw.isClosed() && !clientDemux.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        clientRaw.receive(p);
                        clientDemux.enqueueIncoming(buf, 0, p.getLength(), p.getSocketAddress());
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }, "Test-ClientDemux");
            clientPump.setDaemon(true);
            clientPump.start();

            CompletableFuture<byte[]> echoedByServer = new CompletableFuture<>();
            ServerConnectionConfig srvCfg = ServerConnectionConfig.builder()
                    .maxIdleTimeoutInSeconds(15)
                    .maxOpenPeerInitiatedBidirectionalStreams(4)
                    .maxConnectionBufferSize(4L * 1024L * 1024L)
                    .maxBidirectionalStreamBufferSize(1024L * 1024L)
                    .retryRequired(false)
                    .build();

            try (ServerConnector connector = ServerConnector.builder()
                    .withSocket(serverDemux)
                    .withKeyStore(cert.keyStore(), cert.alias(), cert.password())
                    .withConfiguration(srvCfg)
                    .withLogger(new NullLogger())
                    .build()) {

                connector.registerApplicationProtocol("peerpulsar-mc", (protocol, conn) -> new ApplicationProtocolConnection() {
                    @Override
                    public void acceptPeerInitiatedStream(QuicStream stream) {
                        Thread echoThread = new Thread(() -> {
                            try {
                                InputStream in = stream.getInputStream();
                                OutputStream out = stream.getOutputStream();
                                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int read;
                                while ((read = in.read(buf)) != -1) {
                                    captured.write(buf, 0, read);
                                    out.write(buf, 0, read);
                                    out.flush();
                                }
                                out.close();
                                echoedByServer.complete(captured.toByteArray());
                            } catch (Exception e) {
                                echoedByServer.completeExceptionally(e);
                            }
                        }, "Test-QuicEcho");
                        echoThread.setDaemon(true);
                        echoThread.start();
                    }
                });
                connector.start();

                QuicClientConnection clientConn = QuicClientConnection.newBuilder()
                        .host("127.0.0.1")
                        .port(serverRaw.getLocalPort())
                        .applicationProtocol("peerpulsar-mc")
                        .socketFactory(addr -> clientDemux)
                        .customTrustManager(EphemeralTlsCert.pinnedTrustManager(cert.sha256Hex()))
                        .noServerHostnameVerification()
                        .connectTimeout(Duration.ofSeconds(4))
                        .maxIdleTimeout(Duration.ofSeconds(15))
                        .logger(new NullLogger())
                        .build();
                try {
                    clientConn.connect();
                    QuicStream stream = clientConn.createStream(true);
                    OutputStream qOut = stream.getOutputStream();
                    qOut.write(payload);
                    qOut.close();

                    byte[] clientEcho = stream.getInputStream().readAllBytes();
                    assertArrayEquals(payload, echoedByServer.get(8, TimeUnit.SECONDS));
                    assertArrayEquals(payload, clientEcho);

                    Statistics stats = clientConn.getStats();
                    assertNotNull(stats);
                    assertTrue(stats.packetsSent() > 0);
                } finally {
                    clientConn.close();
                }
            }
        }
    }
}
