package com.p2pworld.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityGuardTest {

    @AfterEach
    void tearDown() {
        PlayerIdentityGuard.onServerStopped();
    }

    @Test
    void preventsDuplicateConcurrentVerificationAttempts() {
        UUID playerUuid = UUID.randomUUID();
        assertTrue(PlayerIdentityGuard.tryBeginVerification(playerUuid));
        assertFalse(PlayerIdentityGuard.tryBeginVerification(playerUuid),
                "Concurrent duplicate verification attempt must be rejected");

        PlayerIdentityGuard.endVerificationAttempt(playerUuid);
        assertTrue(PlayerIdentityGuard.tryBeginVerification(playerUuid),
                "Should allow retry after previous attempt ends without verification");
    }

    @Test
    void disconnectAndServerStopClearVerificationState() {
        UUID playerUuid = UUID.randomUUID();
        assertTrue(PlayerIdentityGuard.tryBeginVerification(playerUuid));
        PlayerIdentityGuard.onPlayerDisconnect(playerUuid);
        assertTrue(PlayerIdentityGuard.tryBeginVerification(playerUuid));

        PlayerIdentityGuard.onServerStopped();
        assertTrue(PlayerIdentityGuard.tryBeginVerification(playerUuid));
    }

    @Test
    void rejectsNullUuidGracefully() {
        assertFalse(PlayerIdentityGuard.tryBeginVerification(null));
        assertFalse(PlayerIdentityGuard.isPendingVerification(null));
        assertDoesNotThrow(() -> PlayerIdentityGuard.endVerificationAttempt(null));
        assertDoesNotThrow(() -> PlayerIdentityGuard.onPlayerDisconnect(null));
    }

    @Test
    void staleAttemptTokenCannotClearNewerVerificationAndAttemptsAreCapped() {
        UUID playerUuid = UUID.randomUUID();
        long token1 = PlayerIdentityGuard.tryBeginVerificationToken(playerUuid);
        assertTrue(token1 > 0L);

        PlayerIdentityGuard.onPlayerDisconnect(playerUuid);
        long token2 = PlayerIdentityGuard.tryBeginVerificationToken(playerUuid);
        assertTrue(token2 > 0L);
        assertNotEquals(token1, token2);

        // Ending token1 must NOT clear token2's in-flight lock
        PlayerIdentityGuard.endVerificationAttempt(playerUuid, token1);
        assertEquals(0L, PlayerIdentityGuard.tryBeginVerificationToken(playerUuid),
                "Stale token must not clear an active newer verification attempt");

        PlayerIdentityGuard.endVerificationAttempt(playerUuid, token2);
        long token3 = PlayerIdentityGuard.tryBeginVerificationToken(playerUuid);
        assertTrue(token3 > 0L);
        PlayerIdentityGuard.endVerificationAttempt(playerUuid, token3);

        // 3rd attempt in the same session (after 2 failed attempts) must be rejected
        assertEquals(0L, PlayerIdentityGuard.tryBeginVerificationToken(playerUuid),
                "Session attempt cap should block unbounded verification spam");
    }
}
