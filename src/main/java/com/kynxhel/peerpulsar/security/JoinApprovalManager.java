package com.kynxhel.peerpulsar.security;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * FIFO queue and timeout coordinator for host Join Approval prompts (Layer 2).
 */
public final class JoinApprovalManager {
    public static final long DEFAULT_TIMEOUT_MS = 24_000L;

    public enum Decision {
        ACCEPT,
        ALWAYS,
        DENY,
        TIMEOUT
    }

    public record PendingRequest(
            String id,
            String playerName,
            String shortFingerprint,
            String fullFingerprint,
            String mojangUuid,
            boolean differentDevice,
            boolean legacyClaim,
            long createdAtMs,
            long expiresAtMs,
            CompletableFuture<Decision> future
    ) {}

    private static final JoinApprovalManager INSTANCE = new JoinApprovalManager();

    private final ConcurrentLinkedDeque<PendingRequest> queue = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PeerPulsar-Approval-Timer");
        t.setDaemon(true);
        return t;
    });
    private volatile Consumer<PendingRequest> listener = null;

    public JoinApprovalManager() {}

    public static JoinApprovalManager getInstance() {
        return INSTANCE;
    }

    public void setListener(Consumer<PendingRequest> listener) {
        this.listener = listener;
    }

    public PendingRequest requestApproval(
            String playerName,
            String fullFingerprint,
            String mojangUuid,
            boolean differentDevice,
            boolean legacyClaim
    ) {
        return requestApproval(playerName, fullFingerprint, mojangUuid, differentDevice, legacyClaim, DEFAULT_TIMEOUT_MS);
    }

    public PendingRequest requestApproval(
            String playerName,
            String fullFingerprint,
            String mojangUuid,
            boolean differentDevice,
            boolean legacyClaim,
            long timeoutMs
    ) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString().substring(0, 8);
        String shortFp = (fullFingerprint != null && fullFingerprint.length() >= 8)
                ? fullFingerprint.substring(0, 8)
                : (fullFingerprint != null ? fullFingerprint : "unknown");
        CompletableFuture<Decision> future = new CompletableFuture<>();
        PendingRequest req = new PendingRequest(
                id,
                playerName,
                shortFp,
                fullFingerprint,
                mojangUuid,
                differentDevice,
                legacyClaim,
                now,
                now + timeoutMs,
                future
        );
        queue.addLast(req);

        timer.schedule(() -> {
            if (queue.remove(req)) {
                future.complete(Decision.TIMEOUT);
            }
        }, Math.max(50L, timeoutMs), TimeUnit.MILLISECONDS);

        Consumer<PendingRequest> cb = this.listener;
        if (cb != null) {
            try {
                cb.accept(req);
            } catch (Exception ignored) {}
        }
        return req;
    }

    public PendingRequest peekCurrent() {
        long now = System.currentTimeMillis();
        while (true) {
            PendingRequest head = queue.peekFirst();
            if (head == null) return null;
            if (head.future().isDone() || now >= head.expiresAtMs()) {
                queue.remove(head);
                if (!head.future().isDone()) {
                    head.future().complete(Decision.TIMEOUT);
                }
                continue;
            }
            return head;
        }
    }

    public int pendingCount() {
        peekCurrent();
        return queue.size();
    }

    public boolean decide(String id, Decision decision) {
        if (id == null || decision == null) return false;
        for (PendingRequest req : queue) {
            if (req.id().equals(id)) {
                queue.remove(req);
                req.future().complete(decision);
                return true;
            }
        }
        return false;
    }

    public boolean decideCurrent(Decision decision) {
        PendingRequest current = peekCurrent();
        return current != null && decide(current.id(), decision);
    }

    public void cancel(String id) {
        if (id == null) return;
        for (PendingRequest req : queue) {
            if (req.id().equals(id)) {
                queue.remove(req);
                req.future().complete(Decision.DENY);
            }
        }
    }

    public void clearAll() {
        PendingRequest req;
        while ((req = queue.pollFirst()) != null) {
            req.future().complete(Decision.DENY);
        }
    }
}
