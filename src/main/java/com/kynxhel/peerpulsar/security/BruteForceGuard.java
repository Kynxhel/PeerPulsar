package com.kynxhel.peerpulsar.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate-limits failed Room Key login attempts per remote IP address:
 * 5 failures within 30 seconds blocks that IP for 5 minutes (or until session reset).
 */
public final class BruteForceGuard {
    public static final int MAX_FAILURES = 5;
    public static final long WINDOW_MS = 30_000L;
    public static final long BLOCK_DURATION_MS = 5 * 60_000L;

    private static final BruteForceGuard GLOBAL = new BruteForceGuard(System::currentTimeMillis);

    private final LongSupplier clockMs;
    private final Map<String, AttemptState> states = new ConcurrentHashMap<>();

    public BruteForceGuard(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    public static BruteForceGuard getGlobal() {
        return GLOBAL;
    }

    public boolean isBlocked(String rawIp) {
        String ip = normalizeIp(rawIp);
        if (ip == null) return false;
        AttemptState state = states.get(ip);
        if (state == null) return false;
        long now = clockMs.getAsLong();
        synchronized (state) {
            if (state.blockedUntilMs > now) {
                return true;
            }
            if (state.blockedUntilMs > 0 && now >= state.blockedUntilMs) {
                state.blockedUntilMs = 0L;
                state.failures.clear();
            }
            return false;
        }
    }

    /**
     * Records a failed room-key attempt. Returns true if this attempt triggered a block.
     */
    public boolean recordFailure(String rawIp) {
        String ip = normalizeIp(rawIp);
        if (ip == null) return false;
        long now = clockMs.getAsLong();
        AttemptState state = states.computeIfAbsent(ip, k -> new AttemptState());
        synchronized (state) {
            if (state.blockedUntilMs > now) {
                return true;
            }
            while (!state.failures.isEmpty() && (now - state.failures.peekFirst()) > WINDOW_MS) {
                state.failures.pollFirst();
            }
            state.failures.addLast(now);
            if (state.failures.size() >= MAX_FAILURES) {
                state.blockedUntilMs = now + BLOCK_DURATION_MS;
                state.failures.clear();
                return true;
            }
            return false;
        }
    }

    public void reset() {
        states.clear();
    }

    private static String normalizeIp(String rawIp) {
        if (rawIp == null) return null;
        String ip = rawIp.trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        int pct = ip.indexOf('%');
        if (pct > 0) ip = ip.substring(0, pct);
        return ip.isEmpty() ? null : ip;
    }

    private static final class AttemptState {
        final Deque<Long> failures = new ArrayDeque<>();
        long blockedUntilMs = 0L;
    }
}
