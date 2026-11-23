package com.p2pworld.client.shared;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GoogleAuthServiceTest {

    private static String buildJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    @Test
    void futureExpRespectsSkewWindow() {
        long futureExpSec = (System.currentTimeMillis() / 1000L) + 120L;
        String jwt = buildJwt("{\"sub\":\"123\",\"exp\":" + futureExpSec + "}");

        assertTrue(GoogleAuthService.isJwtUnexpired(jwt, 0L),
                "Future exp should be valid with zero skew");
        assertTrue(GoogleAuthService.isJwtUnexpired(jwt, 60_000L),
                "Future exp should be valid when skew is smaller than remaining time");
        assertFalse(GoogleAuthService.isJwtUnexpired(jwt, 180_000L),
                "Future exp should be rejected when skew exceeds remaining time");
    }

    @Test
    void expiredExpReturnsFalse() {
        long pastExpSec = (System.currentTimeMillis() / 1000L) - 60L;
        String pastJwt = buildJwt("{\"sub\":\"123\",\"exp\":" + pastExpSec + "}");
        String epochJwt = buildJwt("{\"exp\":0}");

        assertFalse(GoogleAuthService.isJwtUnexpired(pastJwt, 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(pastJwt, 30_000L));
        assertFalse(GoogleAuthService.isJwtUnexpired(epochJwt, 0L));
    }

    @Test
    void missingOrNonNumericExpReturnsFalse() {
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("{\"sub\":\"123\"}"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("{\"exp\":null}"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("{\"exp\":\"not-a-number\"}"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("{\"exp\":true}"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("{\"exp\":{}}"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("[1,2,3]"), 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired(buildJwt("not-valid-json"), 0L));
    }

    @Test
    void malformedJwtReturnsFalse() {
        assertFalse(GoogleAuthService.isJwtUnexpired(null, 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("   ", 0L));

        // Wrong part count
        assertFalse(GoogleAuthService.isJwtUnexpired("singlepart", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("header.payload", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("a.b.c.d", 0L));

        // Blank segments
        assertFalse(GoogleAuthService.isJwtUnexpired(".payload.sig", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("header..sig", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("header.payload.", 0L));
        assertFalse(GoogleAuthService.isJwtUnexpired("..", 0L));

        // Invalid Base64URL payload
        assertFalse(GoogleAuthService.isJwtUnexpired("header.%%%invalid-base64%%%.signature", 0L));
    }

    @Test
    void verifyIdTokenRejectsNullBlankAndOversizedTokens() {
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle(null));
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle(""));
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle("   "));
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle("a".repeat(8193)));
    }

    @Test
    void verifyIdTokenRejectsWhenNoClientIdConfigured() {
        long futureExpSec = (System.currentTimeMillis() / 1000L) + 120L;
        String jwt = buildJwt("{\"sub\":\"123\",\"exp\":" + futureExpSec + "}");

        assertNull(GoogleAuthService.verifyIdTokenWithGoogle(jwt, null));
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle(jwt, ""));
        assertNull(GoogleAuthService.verifyIdTokenWithGoogle(jwt, "   "));
    }

    @Test
    void audienceValidationRequiresExactAudMatch() {
        String cid = "my-client-id.apps.googleusercontent.com";
        assertTrue(GoogleAuthService.isValidAudience(cid, cid, ""));
        assertTrue(GoogleAuthService.isValidAudience(cid, cid, cid));
        assertFalse(GoogleAuthService.isValidAudience(cid, "other-client-id", cid),
                "Matching azp must never override mismatched aud");
        assertFalse(GoogleAuthService.isValidAudience(cid, cid, "other-azp"),
                "Mismatched non-empty azp must be rejected");
        assertFalse(GoogleAuthService.isValidAudience(cid, "", cid));
    }
}
