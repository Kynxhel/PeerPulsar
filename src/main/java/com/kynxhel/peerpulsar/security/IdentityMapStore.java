package com.kynxhel.peerpulsar.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kynxhel.peerpulsar.PeerPulsar;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tracks per-world identity mappings and one-time legacy data migrations in
 * {@code <world>/peerpulsar_identity_map.json}.
 */
public final class IdentityMapStore {
    public static final String FILE_NAME = "peerpulsar_identity_map.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<IdentityRecord>>() {}.getType();

    public record IdentityRecord(
            String keyFingerprint,
            String mojangUuid,
            String effectiveUuid,
            String lastName,
            String migratedFromUuid,
            long lastSeenMs
    ) {
        public String shortFingerprint() {
            return (keyFingerprint != null && keyFingerprint.length() >= 8)
                    ? keyFingerprint.substring(0, 8)
                    : (keyFingerprint != null ? keyFingerprint : "unknown");
        }
    }

    private IdentityMapStore() {}

    public static synchronized List<IdentityRecord> list(Path worldDir) {
        if (worldDir == null) return List.of();
        return List.copyOf(read(worldDir.resolve(FILE_NAME)));
    }

    public static synchronized IdentityRecord findByEffectiveUuid(Path worldDir, UUID uuid) {
        if (worldDir == null || uuid == null) return null;
        String target = uuid.toString();
        for (IdentityRecord r : read(worldDir.resolve(FILE_NAME))) {
            if (r != null && target.equalsIgnoreCase(r.effectiveUuid())) {
                return r;
            }
        }
        return null;
    }

    public static synchronized IdentityRecord findByKeyOrMojang(Path worldDir, String keyFingerprint, String mojangUuid) {
        if (worldDir == null) return null;
        for (IdentityRecord r : read(worldDir.resolve(FILE_NAME))) {
            if (r == null) continue;
            if (mojangUuid != null && !mojangUuid.isBlank() && mojangUuid.equalsIgnoreCase(r.mojangUuid())) {
                return r;
            }
            if (keyFingerprint != null && keyFingerprint.equalsIgnoreCase(r.keyFingerprint())) {
                return r;
            }
        }
        return null;
    }

    public static synchronized void upsert(Path worldDir, IdentityRecord record) {
        if (worldDir == null || record == null || record.effectiveUuid() == null) return;
        Path file = worldDir.resolve(FILE_NAME);
        List<IdentityRecord> list = read(file);
        String preservedMigratedFrom = record.migratedFromUuid();
        for (int i = 0; i < list.size(); i++) {
            IdentityRecord existing = list.get(i);
            if (existing == null) continue;
            boolean sameEffective = record.effectiveUuid().equalsIgnoreCase(existing.effectiveUuid());
            boolean sameKeyAndMojang = record.keyFingerprint() != null
                    && record.keyFingerprint().equalsIgnoreCase(existing.keyFingerprint())
                    && ((record.mojangUuid() == null && existing.mojangUuid() == null)
                    || (record.mojangUuid() != null && record.mojangUuid().equalsIgnoreCase(existing.mojangUuid())));
            if (sameEffective || sameKeyAndMojang) {
                if (preservedMigratedFrom == null) {
                    preservedMigratedFrom = existing.migratedFromUuid();
                }
                list.remove(i);
                i--;
            }
        }
        list.add(new IdentityRecord(
                record.keyFingerprint(),
                record.mojangUuid(),
                record.effectiveUuid(),
                record.lastName(),
                preservedMigratedFrom,
                record.lastSeenMs() > 0 ? record.lastSeenMs() : System.currentTimeMillis()
        ));
        write(file, list);
    }

    private static List<IdentityRecord> read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                List<IdentityRecord> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) return new ArrayList<>(loaded);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Identity] Could not read {}: {}", file, e.getMessage());
        }
        return new ArrayList<>();
    }

    private static void write(Path file, List<IdentityRecord> list) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(list, LIST_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Identity] Could not save {}: {}", file, e.getMessage());
        }
    }
}
