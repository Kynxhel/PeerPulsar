package com.kynxhel.peerpulsar.client.network;

import java.util.Optional;

/**
 * Pure, deterministic state machine for initial UDP engine selection (via connect-time probe)
 * and live mid-game engine switch recommendations with hysteresis and cooldowns.
 */
public final class TransportSwitchAdvisor {
    public static final long SUSTAINED_DEGRADE_MS = 26_000L;
    public static final long SUSTAINED_RECOVERY_MS = 30_000L;
    public static final long COOLDOWN_MS = 300_000L;

    public record ProbeResult(int sent, int received, double avgRttMs, double jitterMs, double lossPct) {}

    public enum ReasonCode {
        HIGH_PING_OR_LOSS,
        JITTER_OR_LOSS,
        PING_RECOVERED
    }

    public record SwitchRecommendation(
            UdpTransportMode targetEngine,
            UdpTransportMode currentEngine,
            double rttMs,
            double jitterMs,
            double lossPct,
            ReasonCode reasonCode,
            long timestampMs
    ) {}

    private UdpTransportMode candidateEngine = null;
    private long candidateSinceMs = -1L;
    private long cooldownUntilMs = 0L;

    public static UdpTransportMode chooseInitialEngine(ProbeResult probe, boolean quicSupportedByHost) {
        if (probe == null || probe.received() <= 0) {
            return UdpTransportMode.RUDP;
        }
        double rtt = probe.avgRttMs();
        double jitter = probe.jitterMs();
        double loss = probe.lossPct();

        if (quicSupportedByHost && (rtt >= 110.0 || loss >= 5.0)) {
            return UdpTransportMode.QUIC;
        }
        if (rtt < 55.0 && loss == 0.0 && jitter < 15.0) {
            return UdpTransportMode.RUDP;
        }
        return UdpTransportMode.KCP;
    }

    public synchronized Optional<SwitchRecommendation> evaluateSample(
            long nowMs,
            UdpTransportMode activeEngine,
            boolean hostSupportsQuic,
            double rttMs,
            double jitterMs,
            double lossPct
    ) {
        if (activeEngine == null || !activeEngine.isConcrete()) {
            return Optional.empty();
        }
        if (nowMs < cooldownUntilMs) {
            candidateEngine = null;
            candidateSinceMs = -1L;
            return Optional.empty();
        }

        CandidateMatch desired = pickDesiredCandidate(activeEngine, hostSupportsQuic, rttMs, jitterMs, lossPct);
        if (desired == null) {
            candidateEngine = null;
            candidateSinceMs = -1L;
            return Optional.empty();
        }

        if (candidateEngine != desired.engine()) {
            candidateEngine = desired.engine();
            candidateSinceMs = nowMs;
            return Optional.empty();
        }

        if (nowMs - candidateSinceMs >= desired.requiredDurationMs()) {
            cooldownUntilMs = nowMs + COOLDOWN_MS;
            candidateEngine = null;
            candidateSinceMs = -1L;
            return Optional.of(new SwitchRecommendation(
                    desired.engine(),
                    activeEngine,
                    rttMs,
                    jitterMs,
                    lossPct,
                    desired.reasonCode(),
                    nowMs
            ));
        }
        return Optional.empty();
    }

    public synchronized void dismiss(long nowMs) {
        cooldownUntilMs = Math.max(cooldownUntilMs, nowMs + COOLDOWN_MS);
        candidateEngine = null;
        candidateSinceMs = -1L;
    }

    public synchronized void reset() {
        candidateEngine = null;
        candidateSinceMs = -1L;
        cooldownUntilMs = 0L;
    }

    private record CandidateMatch(UdpTransportMode engine, long requiredDurationMs, ReasonCode reasonCode) {}

    private static CandidateMatch pickDesiredCandidate(
            UdpTransportMode activeEngine,
            boolean hostSupportsQuic,
            double rttMs,
            double jitterMs,
            double lossPct
    ) {
        if (activeEngine != UdpTransportMode.QUIC && hostSupportsQuic && (rttMs >= 120.0 || lossPct >= 5.0)) {
            return new CandidateMatch(UdpTransportMode.QUIC, SUSTAINED_DEGRADE_MS, ReasonCode.HIGH_PING_OR_LOSS);
        }

        if (activeEngine != UdpTransportMode.RUDP
                && rttMs <= 45.0 && jitterMs <= 10.0 && lossPct <= 0.3) {
            return new CandidateMatch(UdpTransportMode.RUDP, SUSTAINED_RECOVERY_MS, ReasonCode.PING_RECOVERED);
        }

        if (activeEngine == UdpTransportMode.RUDP
                && ((rttMs >= 65.0 && rttMs < 120.0) || jitterMs >= 18.0 || (lossPct >= 1.5 && lossPct < 5.0))) {
            return new CandidateMatch(UdpTransportMode.KCP, SUSTAINED_DEGRADE_MS, ReasonCode.JITTER_OR_LOSS);
        }

        if (activeEngine == UdpTransportMode.QUIC
                && rttMs <= 95.0 && lossPct < 3.0
                && (rttMs >= 50.0 || jitterMs >= 15.0 || lossPct >= 1.0)) {
            return new CandidateMatch(UdpTransportMode.KCP, SUSTAINED_RECOVERY_MS, ReasonCode.PING_RECOVERED);
        }

        return null;
    }
}
