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
import java.util.Locale;
import java.util.UUID;

/**
 * Per-world list of host-approved joiners stored in {@code <world>/peerpulsar_approved.json}.
 * Entries match by player name + Ed25519 key fingerprint (and Mojang UUID in online mode).
 */
public final class ApprovedPlayerStore {
    public static final String FILE_NAME = "peerpulsar_approved.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<ApprovedEntry>>() {}.getType();

    public enum CheckResult {
        APPROVED,
        DIFFERENT_DEVICE,
        UNKNOWN
    }

    public record ApprovedEntry(
            String name,
            String keyFingerprint,
            String mojangUuid,
            String effectiveUuid,
            long approvedAtMs,
            long lastSeenMs
    ) {
        public String shortFingerprint() {
            return (keyFingerprint != null && keyFingerprint.length() >= 8)
                    ? keyFingerprint.substring(0, 8)
                    : (keyFingerprint != null ? keyFingerprint : "unknown");
        }
    }

    private ApprovedPlayerStore() {}

    public static synchronized CheckResult check(Path worldDir, String playerName, String keyFingerprint, String mojangUuid) {
        if (worldDir == null || playerName == null || keyFingerprint == null) {
            return CheckResult.UNKNOWN;
        }
        List<ApprovedEntry> entries = read(worldDir.resolve(FILE_NAME));
        boolean seenSameNameDifferentKey = false;
        for (ApprovedEntry e : entries) {
            if (e == null || e.name() == null || e.keyFingerprint() == null) continue;
            if (e.name().equalsIgnoreCase(playerName)) {
                if (e.keyFingerprint().equalsIgnoreCase(keyFingerprint)) {
                    if (mojangUuid != null && e.mojangUuid() != null && !e.mojangUuid().isBlank()
                            && !e.mojangUuid().equalsIgnoreCase(mojangUuid)) {
                        seenSameNameDifferentKey = true;
                        continue;
                    }
                    return CheckResult.APPROVED;
                } else {
                    seenSameNameDifferentKey = true;
                }
            }
        }
        if (seenSameNameDifferentKey) {
            return CheckResult.DIFFERENT_DEVICE;
        }
        if (mojangUuid != null && !mojangUuid.isBlank()) {
            for (ApprovedEntry e : entries) {
                if (e != null && e.mojangUuid() != null && e.mojangUuid().equalsIgnoreCase(mojangUuid)
                        && e.keyFingerprint() != null && e.keyFingerprint().equalsIgnoreCase(keyFingerprint)) {
                    return CheckResult.APPROVED;
                }
            }
        }
        return CheckResult.UNKNOWN;
    }

    public static synchronized void approve(Path worldDir, ApprovedEntry entry) {
        if (worldDir == null || entry == null || entry.name() == null || entry.keyFingerprint() == null) return;
        Path file = worldDir.resolve(FILE_NAME);
        List<ApprovedEntry> entries = read(file);
        entries.removeIf(e -> e == null || (e.name() != null && e.name().equalsIgnoreCase(entry.name())));
        entries.add(entry);
        write(file, entries);
    }

    public static synchronized void touchLastSeen(Path worldDir, String playerName, String keyFingerprint, String effectiveUuid, String mojangUuid) {
        if (worldDir == null || playerName == null || keyFingerprint == null) return;
        Path file = worldDir.resolve(FILE_NAME);
        List<ApprovedEntry> entries = read(file);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            ApprovedEntry e = entries.get(i);
            if (e != null && e.keyFingerprint() != null && e.keyFingerprint().equalsIgnoreCase(keyFingerprint)
                    && ((e.name() != null && e.name().equalsIgnoreCase(playerName))
                    || (mojangUuid != null && mojangUuid.equalsIgnoreCase(e.mojangUuid())))) {
                entries.set(i, new ApprovedEntry(
                        playerName,
                        e.keyFingerprint(),
                        mojangUuid != null ? mojangUuid : e.mojangUuid(),
                        effectiveUuid != null ? effectiveUuid : e.effectiveUuid(),
                        e.approvedAtMs(),
                        now
                ));
                changed = true;
                break;
            }
        }
        if (changed) {
            write(file, entries);
        }
    }

    public static synchronized boolean remove(Path worldDir, String keyFingerprint, String playerName) {
        if (worldDir == null) return false;
        Path file = worldDir.resolve(FILE_NAME);
        List<ApprovedEntry> entries = read(file);
        boolean removed = entries.removeIf(e -> e != null
                && (keyFingerprint == null || keyFingerprint.equalsIgnoreCase(e.keyFingerprint()))
                && (playerName == null || playerName.equalsIgnoreCase(e.name())));
        if (removed) {
            write(file, entries);
        }
        return removed;
    }

    /**
     * Revokes Always-approval for a player matching {@code playerName} or {@code effectiveUuid}
     * (used when kicking a player so they must be re-approved on their next join).
     */
    public static synchronized boolean removeByNameOrUuid(Path worldDir, String playerName, UUID effectiveUuid) {
        if (worldDir == null || ((playerName == null || playerName.isBlank()) && effectiveUuid == null)) {
            return false;
        }
        String uuidStr = effectiveUuid != null ? effectiveUuid.toString() : null;
        Path file = worldDir.resolve(FILE_NAME);
        List<ApprovedEntry> entries = read(file);
        boolean removed = entries.removeIf(e -> {
            if (e == null) return false;
            boolean nameMatch = playerName != null && !playerName.isBlank()
                    && e.name() != null && playerName.equalsIgnoreCase(e.name());
            boolean uuidMatch = uuidStr != null
                    && ((e.effectiveUuid() != null && uuidStr.equalsIgnoreCase(e.effectiveUuid()))
                    || (e.mojangUuid() != null && uuidStr.equalsIgnoreCase(e.mojangUuid())));
            return nameMatch || uuidMatch;
        });
        if (removed) {
            write(file, entries);
        }
        return removed;
    }

    public static synchronized List<ApprovedEntry> list(Path worldDir) {
        if (worldDir == null) return List.of();
        return List.copyOf(read(worldDir.resolve(FILE_NAME)));
    }

    private static List<ApprovedEntry> read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                List<ApprovedEntry> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) {
                    return new ArrayList<>(loaded);
                }
            }
        } catch (Exception e) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Could not read {}: {}", file, e.getMessage());
        }
        return new ArrayList<>();
    }

    private static void write(Path file, List<ApprovedEntry> entries) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(entries, LIST_TYPE), StandardCharsets.UTF_8);
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
