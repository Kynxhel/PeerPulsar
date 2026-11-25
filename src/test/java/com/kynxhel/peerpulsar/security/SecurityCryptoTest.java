package com.kynxhel.peerpulsar.security;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SecurityCryptoTest {

    @Test
    void roomKeyGenerationValidationAndHmacProof() {
        String key = RoomKeys.generate();
        assertEquals(RoomKeys.KEY_LENGTH, key.length());
        assertTrue(RoomKeys.isValid(key));
        assertTrue(RoomKeys.isValid(key.toLowerCase()));

        assertFalse(RoomKeys.isValid(null));
        assertFalse(RoomKeys.isValid(""));
        assertFalse(RoomKeys.isValid("SHORT"));
        assertFalse(RoomKeys.isValid("INVALID01O")); // 0, 1, O excluded from alphabet

        byte[] nonce = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] proof1 = RoomKeys.loginProof(key, nonce, "Alice");
        byte[] proof2 = RoomKeys.loginProof(key.toLowerCase(), nonce, "alice");
        byte[] proofWrongKey = RoomKeys.loginProof(RoomKeys.generate(), nonce, "Alice");
        byte[] proofWrongName = RoomKeys.loginProof(key, nonce, "Bob");

        assertArrayEquals(proof1, proof2);
        assertFalse(Arrays.equals(proof1, proofWrongKey));
        assertFalse(Arrays.equals(proof1, proofWrongName));
    }

    @Test
    void rotatedRoomKeyImmediatelyRejectsPreviousKeyProof() {
        byte[] nonce = new byte[]{11, 22, 33, 44, 55, 66, 77, 88};
        String oldKey = RoomKeys.generate();
        String newKey = RoomKeys.generate();
        while (newKey.equals(oldKey)) {
            newKey = RoomKeys.generate();
        }
        byte[] oldProof = RoomKeys.loginProof(oldKey, nonce, "PlayerOne");
        byte[] expectedNewProof = RoomKeys.loginProof(newKey, nonce, "PlayerOne");
        assertFalse(Arrays.equals(oldProof, expectedNewProof));
    }

    @Test
    void offlineAuthPolicyEnforcesRoomKeyInAllInternetModes() {
        // Internet modes require Room Key regardless of LAN toggle
        assertTrue(OfflineAuth.isRoomKeyRequiredForHosting(false, false));
        assertTrue(OfflineAuth.isRoomKeyRequiredForHosting(false, true));
        // Local LAN defaults to false, opt-in true
        assertFalse(OfflineAuth.isRoomKeyRequiredForHosting(true, false));
        assertTrue(OfflineAuth.isRoomKeyRequiredForHosting(true, true));

        // Challenge runs in BOTH Online and Offline modes whenever Room Key is required (unless memory connection)
        assertTrue(OfflineAuth.shouldChallenge(true, false, true, false, false));
        assertTrue(OfflineAuth.shouldChallenge(true, false, true, true, false));
        assertFalse(OfflineAuth.shouldChallenge(true, true, true, true, true));
        assertFalse(OfflineAuth.shouldChallenge(false, false, true, true, true));
    }

    @Test
    void ed25519IdentityVerificationWorksAndRejectsTampering() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] nonce = new byte[]{9, 8, 7, 6, 5, 4, 3, 2};
        byte[] payload = RoomKeys.identityPayload(nonce, "Steve");

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(payload);
        byte[] signature = sig.sign();

        assertTrue(ClientIdentity.verify(kp.getPublic().getEncoded(), payload, signature));
        assertFalse(ClientIdentity.verify(kp.getPublic().getEncoded(), RoomKeys.identityPayload(nonce, "Alex"), signature));

        byte[] tampered = signature.clone();
        tampered[0] ^= 0x01;
        assertFalse(ClientIdentity.verify(kp.getPublic().getEncoded(), payload, tampered));
    }

    @Test
    void signalCryptoRoundTripAndTamperRejection() {
        String roomId = "UDP-ABCD-2345-EFGH";
        String topic1 = SignalCrypto.topicFor(roomId);
        String topic2 = SignalCrypto.topicFor("abcd2345efgh");
        assertEquals(topic1, topic2);
        assertTrue(topic1.startsWith("pp-"));
        assertFalse(topic1.toLowerCase().contains("abcd"));

        byte[] key = SignalCrypto.keyFor(roomId);
        byte[] wrongKey = SignalCrypto.keyFor("UDP-9999-8888-7777");

        JsonObject payload = new JsonObject();
        payload.addProperty("role", "join");
        payload.addProperty("ip", "203.0.113.10");
        payload.addProperty("port", 41234);

        String sealed = SignalCrypto.seal(key, payload);
        assertFalse(sealed.contains("203.0.113.10"), "Ciphertext must not leak plaintext IP");

        JsonObject opened = SignalCrypto.open(key, sealed);
        assertNotNull(opened);
        assertEquals("join", opened.get("role").getAsString());
        assertEquals("203.0.113.10", opened.get("ip").getAsString());
        assertEquals(41234, opened.get("port").getAsInt());

        assertNull(SignalCrypto.open(wrongKey, sealed));
        assertNull(SignalCrypto.open(key, payload.toString()));
        assertNull(SignalCrypto.open(key, sealed.replace("\"v\":2", "\"v\":1")));
    }

    @Test
    void nameLockStoreBindsMatchesDetectsMismatchAndRebinds(@TempDir Path worldDir) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        byte[] pubA = gen.generateKeyPair().getPublic().getEncoded();
        byte[] pubB = gen.generateKeyPair().getPublic().getEncoded();

        assertEquals(NameLockStore.Result.BOUND_NEW, NameLockStore.checkAndBind(worldDir, "Alice", pubA));
        assertEquals(NameLockStore.Result.MATCHED, NameLockStore.checkAndBind(worldDir, "alice", pubA));
        assertEquals(NameLockStore.Result.MISMATCH, NameLockStore.checkAndBind(worldDir, "Alice", pubB));

        String fpB = RoomKeys.hex(RoomKeys.sha256(pubB));
        NameLockStore.rebind(worldDir, "Alice", fpB);
        assertEquals(NameLockStore.Result.MATCHED, NameLockStore.checkAndBind(worldDir, "Alice", pubB));

        assertTrue(NameLockStore.removeByName(worldDir, "alice"));
        assertNull(NameLockStore.getBoundFingerprint(worldDir, "Alice"));
    }

    @Test
    void bruteForceGuardBlocksAfterFiveFailuresInThirtySeconds() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BruteForceGuard guard = new BruteForceGuard(clock::get);

        String ip = "/203.0.113.55";
        assertFalse(guard.isBlocked(ip));
        for (int i = 0; i < 4; i++) {
            assertFalse(guard.recordFailure(ip));
            assertFalse(guard.isBlocked(ip));
            clock.addAndGet(2_000L);
        }
        assertTrue(guard.recordFailure(ip)); // 5th failure within 30s
        assertTrue(guard.isBlocked(ip));
        assertFalse(guard.isBlocked("198.51.100.10"));

        // Expires after 5 minutes
        clock.addAndGet(BruteForceGuard.BLOCK_DURATION_MS + 1);
        assertFalse(guard.isBlocked(ip));

        // Reset clears block immediately
        for (int i = 0; i < 5; i++) guard.recordFailure(ip);
        assertTrue(guard.isBlocked(ip));
        guard.reset();
        assertFalse(guard.isBlocked(ip));
    }

    @Test
    void approvedPlayerStoreRoundTripAndDeviceMismatchDetection(@TempDir Path worldDir) {
        String fpA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String fpB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String uuidA = UUID.randomUUID().toString();

        assertEquals(ApprovedPlayerStore.CheckResult.UNKNOWN,
                ApprovedPlayerStore.check(worldDir, "Friend", fpA, null));

        ApprovedPlayerStore.approve(worldDir, new ApprovedPlayerStore.ApprovedEntry(
                "Friend", fpA, uuidA, uuidA, 1000L, 1000L
        ));
        assertEquals(ApprovedPlayerStore.CheckResult.APPROVED,
                ApprovedPlayerStore.check(worldDir, "friend", fpA, uuidA));
        assertEquals(ApprovedPlayerStore.CheckResult.DIFFERENT_DEVICE,
                ApprovedPlayerStore.check(worldDir, "Friend", fpB, null));
        assertEquals(ApprovedPlayerStore.CheckResult.DIFFERENT_DEVICE,
                ApprovedPlayerStore.check(worldDir, "Friend", fpA, UUID.randomUUID().toString()));

        List<ApprovedPlayerStore.ApprovedEntry> entries = ApprovedPlayerStore.list(worldDir);
        assertEquals(1, entries.size());
        assertTrue(ApprovedPlayerStore.remove(worldDir, fpA, "Friend"));
        assertTrue(ApprovedPlayerStore.list(worldDir).isEmpty());
    }

    @Test
    void joinApprovalManagerQueueDecisionsAndTimeout() throws Exception {
        JoinApprovalManager mgr = new JoinApprovalManager();
        var req1 = mgr.requestApproval("Alex", "12345678abcdef00", null, false, false, 5000L);
        var req2 = mgr.requestApproval("Steve", "9999888877776666", null, true, false, 5000L);

        assertEquals(2, mgr.pendingCount());
        assertEquals("Alex", mgr.peekCurrent().playerName());
        assertTrue(mgr.decideCurrent(JoinApprovalManager.Decision.ALWAYS));
        assertEquals(JoinApprovalManager.Decision.ALWAYS, req1.future().get(1, TimeUnit.SECONDS));

        assertEquals(1, mgr.pendingCount());
        assertEquals("Steve", mgr.peekCurrent().playerName());
        assertTrue(mgr.decide(req2.id(), JoinApprovalManager.Decision.DENY));
        assertEquals(JoinApprovalManager.Decision.DENY, req2.future().get(1, TimeUnit.SECONDS));

        var reqTimeout = mgr.requestApproval("LateJoiner", "0000111122223333", null, false, false, 120L);
        assertEquals(JoinApprovalManager.Decision.TIMEOUT, reqTimeout.future().get(2, TimeUnit.SECONDS));
    }

    @Test
    void clientIdentityStatusDetectionAndExportImport(@TempDir Path tempDir) throws Exception {
        Path idFile = tempDir.resolve("identity.json");
        assertEquals(ClientIdentity.Status.NEEDS_FIRST_CREATE, ClientIdentity.inspectStatus(idFile, false));
        assertEquals(ClientIdentity.Status.MISSING_AFTER_INIT, ClientIdentity.inspectStatus(idFile, true));

        // Corrupt JSON must be flagged, not silently overwritten
        Files.writeString(idFile, "{corrupt json", StandardCharsets.UTF_8);
        assertEquals(ClientIdentity.Status.CORRUPT, ClientIdentity.inspectStatus(idFile, true));
        assertNull(ClientIdentity.tryLoad(idFile));

        // Create valid identity and export/import round-trip
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        JsonObject validObj = new JsonObject();
        validObj.addProperty("public", java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        validObj.addProperty("private", java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        Files.writeString(idFile, validObj.toString(), StandardCharsets.UTF_8);

        assertEquals(ClientIdentity.Status.OK, ClientIdentity.inspectStatus(idFile, true));

        Path exported = tempDir.resolve("backup/my-id.json");
        ClientIdentity.exportFromFile(idFile, exported);
        assertTrue(Files.isRegularFile(exported));

        Path importedTarget = tempDir.resolve("restored/identity.json");
        ClientIdentity imported = ClientIdentity.importIntoFile(exported, importedTarget);
        assertArrayEquals(kp.getPublic().getEncoded(), imported.publicKeyEncoded());
        assertEquals(ClientIdentity.Status.OK, ClientIdentity.inspectStatus(importedTarget, true));

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path defaultBackup = ClientIdentity.defaultBackupFile();
            assertEquals(Path.of(userHome, ".peerpulsar", "peerpulsar-identity-backup.json"), defaultBackup);
        }
        try {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(exported)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(importedTarget)));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows)
        }
    }

    @Test
    void channelBindingRejectsMitmPublicKeySwap() throws Exception {
        byte[] nonce = new byte[32];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) (i + 1);
        }
        byte[] hostPubKeyHash = RoomKeys.sha256("host-rsa-public-key".getBytes(StandardCharsets.UTF_8));
        byte[] mitmPubKeyHash = RoomKeys.sha256("mitm-rsa-public-key".getBytes(StandardCharsets.UTF_8));
        String roomKey = RoomKeys.generate();

        // 1. Room key HMAC proof must differ if the server public key hash is swapped
        byte[] boundProofHost = RoomKeys.loginProof(roomKey, nonce, "Steve", hostPubKeyHash);
        byte[] boundProofHostCopy = RoomKeys.loginProof(roomKey, nonce, "steve", hostPubKeyHash.clone());
        byte[] boundProofMitm = RoomKeys.loginProof(roomKey, nonce, "Steve", mitmPubKeyHash);
        byte[] unboundProof = RoomKeys.loginProof(roomKey, nonce, "Steve", null);

        assertArrayEquals(boundProofHost, boundProofHostCopy);
        assertFalse(Arrays.equals(boundProofHost, boundProofMitm));
        assertFalse(Arrays.equals(boundProofHost, unboundProof));

        // 2. Ed25519 identity signature bound to MITM's public key must fail host verification
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] mitmSignedPayload = RoomKeys.identityPayload(nonce, "Steve", mitmPubKeyHash);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(mitmSignedPayload);
        byte[] mitmSignature = sig.sign();

        assertTrue(ClientIdentity.verify(kp.getPublic().getEncoded(), mitmSignedPayload, mitmSignature));
        assertFalse(ClientIdentity.verify(
                kp.getPublic().getEncoded(),
                RoomKeys.identityPayload(nonce, "Steve", hostPubKeyHash),
                mitmSignature
        ));
        assertFalse(ClientIdentity.verify(
                kp.getPublic().getEncoded(),
                RoomKeys.identityPayload(nonce, "Steve", null),
                mitmSignature
        ));
    }
}
