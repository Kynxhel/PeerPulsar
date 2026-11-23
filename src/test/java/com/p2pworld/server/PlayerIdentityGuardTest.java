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
}
