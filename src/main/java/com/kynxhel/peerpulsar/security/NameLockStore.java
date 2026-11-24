package com.kynxhel.peerpulsar.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kynxhel.peerpulsar.PeerPulsar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-world "first come, first served" name locks for Offline LAN Auth.
 * Stores {@code lowercase name -> SHA-256(public key)} in {@code <world>/peerpulsar_name_locks.json}.
 * Only fingerprints are stored; delete the file (or a single entry) to release a name.
 */
public final class NameLockStore {
    public static final String FILE_NAME = "peerpulsar_name_locks.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<TreeMap<String, String>>() {}.getType();

    public enum Result { BOUND_NEW, MATCHED, MISMATCH }

    private NameLockStore() {}

    public static synchronized Result checkAndBind(MinecraftServer server, String playerName, byte[] encodedPublicKey) {
        return checkAndBind(server.getWorldPath(LevelResource.ROOT), playerName, encodedPublicKey);
    }

    public static synchronized Result checkAndBind(Path worldDir, String playerName, byte[] encodedPublicKey) {
        Path file = worldDir.resolve(FILE_NAME);
        String key = playerName.toLowerCase(Locale.ROOT);
        String fingerprint = RoomKeys.hex(RoomKeys.sha256(encodedPublicKey));

        Map<String, String> locks = read(file);
        String existing = locks.get(key);
        if (existing != null) {
            return existing.equalsIgnoreCase(fingerprint) ? Result.MATCHED : Result.MISMATCH;
        }
        locks.put(key, fingerprint);
        write(file, locks);
        PeerPulsar.LOGGER.info("[PeerPulsar-Auth] Name '{}' is now locked to identity {}", playerName, fingerprint.substring(0, 12));
        return Result.BOUND_NEW;
    }

    public static synchronized String getBoundFingerprint(Path worldDir, String playerName) {
        if (worldDir == null || playerName == null) return null;
        Map<String, String> locks = read(worldDir.resolve(FILE_NAME));
        return locks.get(playerName.toLowerCase(Locale.ROOT));
    }

    public static synchronized void rebind(Path worldDir, String playerName, String fingerprint) {
        if (worldDir == null || playerName == null || fingerprint == null) return;
        Path file = worldDir.resolve(FILE_NAME);
        Map<String, String> locks = read(file);
        locks.put(playerName.toLowerCase(Locale.ROOT), fingerprint);
        write(file, locks);
    }

    public static synchronized boolean removeByName(Path worldDir, String playerName) {
        if (worldDir == null || playerName == null) return false;
        Path file = worldDir.resolve(FILE_NAME);
        Map<String, String> locks = read(file);
        if (locks.remove(playerName.toLowerCase(Locale.ROOT)) != null) {
            write(file, locks);
            return true;
        }
        return false;
    }

    public static synchronized boolean removeByFingerprint(Path worldDir, String fingerprint) {
        if (worldDir == null || fingerprint == null) return false;
        Path file = worldDir.resolve(FILE_NAME);
        Map<String, String> locks = read(file);
        boolean changed = locks.entrySet().removeIf(e -> fingerprint.equalsIgnoreCase(e.getValue()));
        if (changed) {
            write(file, locks);
        }
        return changed;
    }

    private static Map<String, String> read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                Map<String, String> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
                if (m != null) return new TreeMap<>(m);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Could not read {}: {}", file, e.getMessage());
        }
        return new TreeMap<>();
    }

    private static void write(Path file, Map<String, String> locks) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(locks, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Could not save {}: {}", file, e.getMessage());
        }
    }
}
