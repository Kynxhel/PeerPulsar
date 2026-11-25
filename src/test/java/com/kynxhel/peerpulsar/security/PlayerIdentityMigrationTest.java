package com.kynxhel.peerpulsar.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerIdentityMigrationTest {

    @Test
    void deterministicKeyUuidIsStableAcrossRenamesAndDistinctAcrossKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        byte[] keyA = gen.generateKeyPair().getPublic().getEncoded();
        byte[] keyB = gen.generateKeyPair().getPublic().getEncoded();

        UUID uuidA1 = PlayerDataMigrator.deterministicKeyUuid(keyA);
        UUID uuidA2 = PlayerDataMigrator.deterministicKeyUuid(keyA);
        UUID uuidB = PlayerDataMigrator.deterministicKeyUuid(keyB);

        assertEquals(uuidA1, uuidA2);
        assertEquals(3, uuidA1.version());
        assertNotEquals(uuidA1, uuidB);
    }

    @Test
    void legacyOfflineWorldDataMigratesOnceWithBackupAndPreservesOriginal(@TempDir Path worldDir) throws Exception {
        String playerName = "LegacyFriend";
        UUID legacyUuid = PlayerDataMigrator.legacyOfflineUuid(playerName);
        byte[] pubKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        String fp = RoomKeys.hex(RoomKeys.sha256(pubKey));
        UUID targetUuid = PlayerDataMigrator.deterministicKeyUuid(pubKey);

        byte[] datBytes = "mock-nbt-inventory-diamond-sword".getBytes(StandardCharsets.UTF_8);
        byte[] advBytes = "{\"minecraft:story/root\":{\"done\":true}}".getBytes(StandardCharsets.UTF_8);
        byte[] statBytes = "{\"stats\":{\"minecraft:mined\":{\"minecraft:stone\":42}}}".getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(worldDir.resolve("playerdata"));
        Files.createDirectories(worldDir.resolve("advancements"));
        Files.createDirectories(worldDir.resolve("stats"));
        Path legacyDat = worldDir.resolve("playerdata").resolve(legacyUuid + ".dat");
        Path legacyAdv = worldDir.resolve("advancements").resolve(legacyUuid + ".json");
        Path legacyStat = worldDir.resolve("stats").resolve(legacyUuid + ".json");
        Files.write(legacyDat, datBytes);
        Files.write(legacyAdv, advBytes);
        Files.write(legacyStat, statBytes);

        assertTrue(PlayerDataMigrator.hasUnclaimedLegacyData(worldDir, targetUuid, playerName));

        // 1. Unauthorized key must NOT claim legacy data
        PlayerDataMigrator.MigrationResult blocked = PlayerDataMigrator.migrateLegacyDataIfNeeded(
                worldDir, targetUuid, playerName, fp, null, false
        );
        assertEquals(PlayerDataMigrator.MigrationStatus.UNAUTHORIZED_LEGACY_CLAIM, blocked.status());
        assertFalse(Files.exists(worldDir.resolve("playerdata").resolve(targetUuid + ".dat")));

        // 2. Authorized claim copies data, leaves original intact, creates backup & identity map
        PlayerDataMigrator.MigrationResult migrated = PlayerDataMigrator.migrateLegacyDataIfNeeded(
                worldDir, targetUuid, playerName, fp, null, true
        );
        assertEquals(PlayerDataMigrator.MigrationStatus.MIGRATED, migrated.status());
        assertEquals(3, migrated.filesCopied());
        assertNotNull(migrated.backupDir());
        assertTrue(Files.isRegularFile(migrated.backupDir().resolve("playerdata").resolve(legacyUuid + ".dat")));

        Path targetDat = worldDir.resolve("playerdata").resolve(targetUuid + ".dat");
        assertArrayEquals(datBytes, Files.readAllBytes(targetDat));
        assertArrayEquals(advBytes, Files.readAllBytes(worldDir.resolve("advancements").resolve(targetUuid + ".json")));
        assertArrayEquals(statBytes, Files.readAllBytes(worldDir.resolve("stats").resolve(targetUuid + ".json")));
        assertTrue(Files.isRegularFile(legacyDat), "Legacy .dat must never be deleted");

        IdentityMapStore.IdentityRecord mapped = IdentityMapStore.findByEffectiveUuid(worldDir, targetUuid);
        assertNotNull(mapped);
        assertEquals(legacyUuid.toString(), mapped.migratedFromUuid());

        // 3. Modify target .dat as if player played, then rejoin -> must NOT re-copy or overwrite
        byte[] updatedBytes = "updated-inventory-netherite".getBytes(StandardCharsets.UTF_8);
        Files.write(targetDat, updatedBytes);
        PlayerDataMigrator.MigrationResult secondJoin = PlayerDataMigrator.migrateLegacyDataIfNeeded(
                worldDir, targetUuid, playerName, fp, null, true
        );
        assertEquals(PlayerDataMigrator.MigrationStatus.ALREADY_MIGRATED, secondJoin.status());
        assertArrayEquals(updatedBytes, Files.readAllBytes(targetDat));
    }

    @Test
    void hostTransferDataCreatesBackupAndCopiesWithoutDeletingSource(@TempDir Path worldDir) throws Exception {
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();
        byte[] oldBytes = "old-key-playerdata".getBytes(StandardCharsets.UTF_8);
        byte[] prevNewBytes = "new-key-empty-start".getBytes(StandardCharsets.UTF_8);

        Files.createDirectories(worldDir.resolve("playerdata"));
        Path oldDat = worldDir.resolve("playerdata").resolve(oldUuid + ".dat");
        Path newDat = worldDir.resolve("playerdata").resolve(newUuid + ".dat");
        Files.write(oldDat, oldBytes);
        Files.write(newDat, prevNewBytes);

        PlayerDataMigrator.TransferResult res = PlayerDataMigrator.transferPlayerData(worldDir, oldUuid, newUuid);
        assertTrue(res.success());
        assertEquals(1, res.filesCopied());
        assertArrayEquals(oldBytes, Files.readAllBytes(newDat));
        assertArrayEquals(oldBytes, Files.readAllBytes(oldDat));
        assertArrayEquals(prevNewBytes, Files.readAllBytes(res.backupDir().resolve("playerdata").resolve(newUuid + ".dat")));
    }

    @Test
    void modernMc263PlayersSubdirectoryLayoutWorksForMigrationAndTransfer(@TempDir Path worldDir) throws Exception {
        UUID oldUuid = UUID.randomUUID();
        UUID newUuid = UUID.randomUUID();
        byte[] oldBytes = "mc263-player-data".getBytes(StandardCharsets.UTF_8);
        byte[] advBytes = "{\"adv\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] statBytes = "{\"stat\":1}".getBytes(StandardCharsets.UTF_8);

        Path dataDir = worldDir.resolve("players").resolve("data");
        Path advDir = worldDir.resolve("players").resolve("advancements");
        Path statDir = worldDir.resolve("players").resolve("stats");
        Files.createDirectories(dataDir);
        Files.createDirectories(advDir);
        Files.createDirectories(statDir);
        Files.write(dataDir.resolve(oldUuid + ".dat"), oldBytes);
        Files.write(advDir.resolve(oldUuid + ".json"), advBytes);
        Files.write(statDir.resolve(oldUuid + ".json"), statBytes);

        assertEquals(dataDir, PlayerDataMigrator.resolvePlayerDataDir(worldDir));
        PlayerDataMigrator.TransferResult res = PlayerDataMigrator.transferPlayerData(worldDir, oldUuid, newUuid);
        assertTrue(res.success());
        assertEquals(3, res.filesCopied());
        assertArrayEquals(oldBytes, Files.readAllBytes(dataDir.resolve(newUuid + ".dat")));
        assertArrayEquals(advBytes, Files.readAllBytes(advDir.resolve(newUuid + ".json")));
        assertArrayEquals(statBytes, Files.readAllBytes(statDir.resolve(newUuid + ".json")));
    }
}
