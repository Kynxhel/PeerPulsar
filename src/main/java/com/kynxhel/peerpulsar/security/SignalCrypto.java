package com.kynxhel.peerpulsar.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * End-to-end protection for UDP Hole Punch signaling messages relayed through the public ntfy.sh service.
 * <p>
 * The ntfy topic name is a one-way hash of the room ID, and every message is AES-256-GCM encrypted and
 * authenticated with a key derived from the room ID. ntfy.sh (or anyone watching the topic) therefore cannot
 * read the peers' IP addresses or inject forged connection offers.
 */
public final class SignalCrypto {
    private static final SecureRandom RNG = new SecureRandom();
    private static final long MAX_CLOCK_SKEW_SEC = 180;

    private SignalCrypto() {}

    public static String topicFor(String roomId) {
        byte[] h = RoomKeys.sha256(("peerpulsar-topic-v1|" + normalize(roomId)).getBytes(StandardCharsets.UTF_8));
        return "pp-" + RoomKeys.hex(h).substring(0, 40);
    }

    public static byte[] keyFor(String roomId) {
        return RoomKeys.sha256(("peerpulsar-signal-v1|" + normalize(roomId)).getBytes(StandardCharsets.UTF_8));
    }

    /** Encrypts {@code payload} (a timestamp is added) into a compact JSON envelope. */
    public static String seal(byte[] key, JsonObject payload) {
        try {
            JsonObject p = payload.deepCopy();
            p.addProperty("ts", System.currentTimeMillis() / 1000L);
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(p.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject env = new JsonObject();
            env.addProperty("v", 2);
            env.addProperty("n", Base64.getEncoder().encodeToString(iv));
            env.addProperty("c", Base64.getEncoder().encodeToString(ct));
            return env.toString();
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** Decrypts and authenticates an envelope; returns {@code null} for anything forged, stale or malformed. */
    public static JsonObject open(byte[] key, String envelope) {
        try {
            JsonObject env = JsonParser.parseString(envelope).getAsJsonObject();
            if (!env.has("v") || env.get("v").getAsInt() != 2) return null;
            byte[] iv = Base64.getDecoder().decode(env.get("n").getAsString());
            byte[] ct = Base64.getDecoder().decode(env.get("c").getAsString());
            if (iv.length != 12 || ct.length > 8192) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            JsonObject p = JsonParser.parseString(new String(c.doFinal(ct), StandardCharsets.UTF_8)).getAsJsonObject();
            long ts = p.has("ts") ? p.get("ts").getAsLong() : 0L;
            if (Math.abs(System.currentTimeMillis() / 1000L - ts) > MAX_CLOCK_SKEW_SEC) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String roomId) {
        return roomId == null ? "" : roomId.toUpperCase(Locale.ROOT).replace("UDP-", "").replace("-", "").trim();
    }
}
