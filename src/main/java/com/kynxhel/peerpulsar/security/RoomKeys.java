package com.kynxhel.peerpulsar.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Room key helpers for Offline LAN Auth.
 * <p>
 * The room key is a short random secret appended to the room code (e.g. {@code P2P-XXXX-XXXX-XXXX#ABCDE23456}).
 * It never leaves the clients in plaintext: joiners prove they know it with an HMAC over a fresh server nonce.
 */
public final class RoomKeys {
    public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    public static final int KEY_LENGTH = 10;
    private static final SecureRandom RNG = new SecureRandom();

    private RoomKeys() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String normalize(String key) {
        if (key == null) return null;
        String k = key.trim().toUpperCase(Locale.ROOT);
        return k.isEmpty() ? null : k;
    }

    public static boolean isValid(String key) {
        String k = normalize(key);
        if (k == null || k.length() < 8 || k.length() > 32) return false;
        for (int i = 0; i < k.length(); i++) {
            if (ALPHABET.indexOf(k.charAt(i)) < 0) return false;
        }
        return true;
    }

    /** HMAC proof that the joiner knows the room key, bound to the server nonce and the login name. */
    public static byte[] loginProof(String roomKey, byte[] nonce, String playerName) {
        return loginProof(roomKey, nonce, playerName, null);
    }

    /**
     * HMAC proof that the joiner knows the room key, bound to the server nonce, login name,
     * and the host's encryption public key hash (channel binding).
     */
    public static byte[] loginProof(String roomKey, byte[] nonce, String playerName, byte[] serverPubKeyHash) {
        try {
            byte[] keyMaterial = sha256(("peerpulsar-room-v2|" + normalize(roomKey)).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
            mac.update(nonce);
            mac.update((byte) '|');
            mac.update(playerName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            if (serverPubKeyHash != null && serverPubKeyHash.length > 0) {
                mac.update(serverPubKeyHash);
            }
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Payload signed with the joiner's persistent identity key. */
    public static byte[] identityPayload(byte[] nonce, String playerName) {
        return identityPayload(nonce, playerName, null);
    }

    /** Payload signed with the joiner's persistent identity key, bound to the host's encryption public key hash. */
    public static byte[] identityPayload(byte[] nonce, String playerName, byte[] serverPubKeyHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("peerpulsar-login-v2|".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(nonce);
        out.write('|');
        out.writeBytes(playerName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        out.write('|');
        if (serverPubKeyHash != null && serverPubKeyHash.length > 0) {
            out.writeBytes(serverPubKeyHash);
        }
        return out.toByteArray();
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
