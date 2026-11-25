package com.kynxhel.peerpulsar.security;

import com.kynxhel.peerpulsar.PeerPulsar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Deterministic key-based UUID derivation and safe, backup-first player data migration.
 * <p>
 * Golden rule: existing {@code playerdata/*.dat}, {@code advancements/*.json}, or {@code stats/*.json}
 * files are NEVER deleted or silently overwritten during migration.
 */
public final class PlayerDataMigrator {
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");

    public enum MigrationStatus {
        MIGRATED,
        ALREADY_EXISTS,
        ALREADY_MIGRATED,
        NO_LEGACY_DATA,
        UNAUTHORIZED_LEGACY_CLAIM,
        FAILED
    }

    public record MigrationResult(MigrationStatus status, UUID legacyUuid, UUID targetUuid, Path backupDir, int filesCopied) {}

    public record TransferResult(boolean success, String message, Path backupDir, int filesCopied) {}

    private PlayerDataMigrator() {}

    /**
     * Resolves the playerdata directory for Minecraft 26.3 ({@code players/data}),
     * falling back to legacy {@code playerdata} when present (e.g. upgraded worlds or tests).
     */
    public static Path resolvePlayerDataDir(Path worldDir) {
        Path modern = worldDir.resolve("players").resolve("data");
        if (Files.isDirectory(modern)) {
            return modern;
        }
        Path legacy = worldDir.resolve("playerdata");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return modern;
    }

    public static Path resolveAdvancementsDir(Path worldDir) {
        Path modern = worldDir.resolve("players").resolve("advancements");
        if (Files.isDirectory(modern)) {
            return modern;
        }
        Path legacy = worldDir.resolve("advancements");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return modern;
    }

    public static Path resolveStatsDir(Path worldDir) {
        Path modern = worldDir.resolve("players").resolve("stats");
        if (Files.isDirectory(modern)) {
            return modern;
        }
        Path legacy = worldDir.resolve("stats");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return modern;
    }

    public static UUID deterministicKeyUuid(byte[] encodedPublicKey) {
        String fingerprint = RoomKeys.hex(RoomKeys.sha256(encodedPublicKey));
        return UUID.nameUUIDFromBytes(("PeerPulsar:" + fingerprint).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID legacyOfflineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean hasUnclaimedLegacyData(Path worldDir, UUID targetUuid, String playerName) {
        if (worldDir == null || targetUuid == null || playerName == null || playerName.isBlank()) {
            return false;
        }
        UUID legacyUuid = legacyOfflineUuid(playerName);
        if (legacyUuid.equals(targetUuid)) {
            return false;
        }
        Path playerDataDir = resolvePlayerDataDir(worldDir);
        Path targetDat = playerDataDir.resolve(targetUuid + ".dat");
        Path legacyDat = playerDataDir.resolve(legacyUuid + ".dat");
        return !Files.exists(targetDat) && Files.isRegularFile(legacyDat);
    }

    /**
     * Copies legacy offline-name player data to {@code targetUuid} once, backing up all involved files first.
     * Existing files for {@code targetUuid} and the original {@code legacyUuid} files are never deleted or overwritten.
     */
    public static synchronized MigrationResult migrateLegacyDataIfNeeded(
            Path worldDir,
            UUID targetUuid,
            String playerName,
            String keyFingerprint,
            String mojangUuid,
            boolean authorizedToClaimLegacy
    ) {
        if (worldDir == null || targetUuid == null || playerName == null || playerName.isBlank()) {
            return new MigrationResult(MigrationStatus.NO_LEGACY_DATA, null, targetUuid, null, 0);
        }

        UUID legacyUuid = legacyOfflineUuid(playerName);
        if (legacyUuid.equals(targetUuid)) {
            return new MigrationResult(MigrationStatus.ALREADY_EXISTS, legacyUuid, targetUuid, null, 0);
        }

        IdentityMapStore.IdentityRecord existingMap = IdentityMapStore.findByEffectiveUuid(worldDir, targetUuid);
        if (existingMap != null && existingMap.migratedFromUuid() != null && !existingMap.migratedFromUuid().isBlank()) {
            return new MigrationResult(MigrationStatus.ALREADY_MIGRATED, legacyUuid, targetUuid, null, 0);
        }

        Path playerDataDir = resolvePlayerDataDir(worldDir);
        Path advancementsDir = resolveAdvancementsDir(worldDir);
        Path statsDir = resolveStatsDir(worldDir);
        Path targetDat = playerDataDir.resolve(targetUuid + ".dat");
        if (Files.exists(targetDat)) {
            return new MigrationResult(MigrationStatus.ALREADY_EXISTS, legacyUuid, targetUuid, null, 0);
        }

        Path legacyDat = playerDataDir.resolve(legacyUuid + ".dat");
        if (!Files.isRegularFile(legacyDat)) {
            return new MigrationResult(MigrationStatus.NO_LEGACY_DATA, legacyUuid, targetUuid, null, 0);
        }

        if (!authorizedToClaimLegacy) {
            PeerPulsar.LOGGER.warn("[PeerPulsar-Identity] Blocked unauthorized legacy data claim for '{}' ({} -> {})",
                    playerName, legacyUuid, targetUuid);
            return new MigrationResult(MigrationStatus.UNAUTHORIZED_LEGACY_CLAIM, legacyUuid, targetUuid, null, 0);
        }

        Path backupDir = worldDir.resolve("peerpulsar_backups").resolve(LocalDateTime.now().format(BACKUP_TS));
        try {
            int backedUp = backupUuidFiles(worldDir, legacyUuid, backupDir);
            int copied = 0;

            copied += copyIfAbsent(legacyDat, targetDat);
            copied += copyIfAbsent(
                    playerDataDir.resolve(legacyUuid + ".dat_old"),
                    playerDataDir.resolve(targetUuid + ".dat_old")
            );
            copied += copyIfAbsent(
                    advancementsDir.resolve(legacyUuid + ".json"),
                    advancementsDir.resolve(targetUuid + ".json")
            );
            copied += copyIfAbsent(
                    statsDir.resolve(legacyUuid + ".json"),
                    statsDir.resolve(targetUuid + ".json")
            );

            IdentityMapStore.upsert(worldDir, new IdentityMapStore.IdentityRecord(
                    keyFingerprint,
                    mojangUuid,
                    targetUuid.toString(),
                    playerName,
                    legacyUuid.toString(),
                    System.currentTimeMillis()
            ));

            PeerPulsar.LOGGER.info(
                    "[PeerPulsar-Identity] Migrated legacy data for '{}' ({} -> {}): {} file(s) copied, {} backed up in {}",
                    playerName, legacyUuid, targetUuid, copied, backedUp, backupDir
            );
            return new MigrationResult(MigrationStatus.MIGRATED, legacyUuid, targetUuid, backupDir, copied);
        } catch (Exception e) {
            PeerPulsar.LOGGER.error("[PeerPulsar-Identity] Migration failed for '{}': {}", playerName, e.getMessage());
            return new MigrationResult(MigrationStatus.FAILED, legacyUuid, targetUuid, backupDir, 0);
        }
    }

    /**
     * Host-triggered data transfer from an old UUID (e.g. lost identity key) to a new UUID.
     * Backs up both UUIDs first and copies without deleting the source files.
     */
    public static synchronized TransferResult transferPlayerData(Path worldDir, UUID fromUuid, UUID toUuid) {
        if (worldDir == null || fromUuid == null || toUuid == null) {
            return new TransferResult(false, "Missing world or UUID.", null, 0);
        }
        if (fromUuid.equals(toUuid)) {
            return new TransferResult(false, "Source and target UUIDs are identical.", null, 0);
        }

        Path playerDataDir = resolvePlayerDataDir(worldDir);
        Path advancementsDir = resolveAdvancementsDir(worldDir);
        Path statsDir = resolveStatsDir(worldDir);
        Path sourceDat = playerDataDir.resolve(fromUuid + ".dat");
        if (!Files.isRegularFile(sourceDat)) {
            return new TransferResult(false, "No playerdata found for source UUID " + fromUuid, null, 0);
        }

        Path backupDir = worldDir.resolve("peerpulsar_backups").resolve("transfer_" + LocalDateTime.now().format(BACKUP_TS));
        try {
            backupUuidFiles(worldDir, fromUuid, backupDir);
            backupUuidFiles(worldDir, toUuid, backupDir);

            int copied = 0;
            copied += copyOverwrite(sourceDat, playerDataDir.resolve(toUuid + ".dat"));
            copied += copyOverwrite(
                    playerDataDir.resolve(fromUuid + ".dat_old"),
                    playerDataDir.resolve(toUuid + ".dat_old")
            );
            copied += copyOverwrite(
                    advancementsDir.resolve(fromUuid + ".json"),
                    advancementsDir.resolve(toUuid + ".json")
            );
            copied += copyOverwrite(
                    statsDir.resolve(fromUuid + ".json"),
                    statsDir.resolve(toUuid + ".json")
            );

            PeerPulsar.LOGGER.info(
                    "[PeerPulsar-Identity] Transferred player data {} -> {} ({} file(s) copied, backup at {})",
                    fromUuid, toUuid, copied, backupDir
            );
            return new TransferResult(true, "Transferred " + copied + " file(s) with backup.", backupDir, copied);
        } catch (Exception e) {
            PeerPulsar.LOGGER.error("[PeerPulsar-Identity] Data transfer failed ({} -> {}): {}", fromUuid, toUuid, e.getMessage());
            return new TransferResult(false, "Transfer failed: " + e.getMessage(), backupDir, 0);
        }
    }

    private static int backupUuidFiles(Path worldDir, UUID uuid, Path backupDir) throws IOException {
        Path playerDataDir = resolvePlayerDataDir(worldDir);
        Path advancementsDir = resolveAdvancementsDir(worldDir);
        Path statsDir = resolveStatsDir(worldDir);
        int count = 0;
        count += backupFile(playerDataDir.resolve(uuid + ".dat"), backupDir.resolve("playerdata").resolve(uuid + ".dat"));
        count += backupFile(playerDataDir.resolve(uuid + ".dat_old"), backupDir.resolve("playerdata").resolve(uuid + ".dat_old"));
        count += backupFile(advancementsDir.resolve(uuid + ".json"), backupDir.resolve("advancements").resolve(uuid + ".json"));
        count += backupFile(statsDir.resolve(uuid + ".json"), backupDir.resolve("stats").resolve(uuid + ".json"));
        return count;
    }

    private static int backupFile(Path src, Path dst) throws IOException {
        if (!Files.isRegularFile(src)) return 0;
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }

    private static int copyIfAbsent(Path src, Path dst) throws IOException {
        if (!Files.isRegularFile(src) || Files.exists(dst)) return 0;
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }

    private static int copyOverwrite(Path src, Path dst) throws IOException {
        if (!Files.isRegularFile(src)) return 0;
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }
}
