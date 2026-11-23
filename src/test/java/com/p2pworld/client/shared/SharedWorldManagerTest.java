package com.p2pworld.client.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SharedWorldManagerTest {

    private static final Pattern WINDOWS_RESERVED_DEVICE =
            Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    @Test
    void preservesNormalWorldNames() {
        assertEquals("SurvivalWorld", SharedWorldManager.sanitizeName("SurvivalWorld"));
        assertEquals("My World 2026", SharedWorldManager.sanitizeName("My World 2026"));
        assertEquals("world_1-test.v2", SharedWorldManager.sanitizeName("world_1-test.v2"));
        assertEquals("Trimmed Name", SharedWorldManager.sanitizeName("  Trimmed Name  "));
    }

    @Test
    void preservesTurkishAndUnicodeWorldNames() {
        assertEquals("Dünyam", SharedWorldManager.sanitizeName("Dünyam"));
        assertEquals("Ağaç Ev", SharedWorldManager.sanitizeName("Ağaç Ev"));
        assertEquals("Şehir Çiftliği ĞIİÖŞÜ", SharedWorldManager.sanitizeName("Şehir Çiftliği ĞIİÖŞÜ"));
    }

    @Test
    void neutralizesPathTraversalAndSpecialCharacters() {
        for (String input : new String[]{"../evil", "..\\evil", "../../etc/passwd"}) {
            String sanitized = SharedWorldManager.sanitizeName(input);
            assertFalse(sanitized.isEmpty(), "Expected non-empty sanitized name for: " + input);
            assertFalse(sanitized.contains("/"), "Slash should be removed: " + sanitized);
            assertFalse(sanitized.contains("\\"), "Backslash should be removed: " + sanitized);
            assertFalse(sanitized.contains(".."), "Parent traversal should be removed: " + sanitized);
            assertFalse(sanitized.startsWith("."), "Leading dots should be stripped: " + sanitized);
        }
        assertTrue(SharedWorldManager.sanitizeName("../evil").contains("evil"));

        String stripped = SharedWorldManager.sanitizeName("My<World>:Test|1?*");
        assertFalse(stripped.isEmpty());
        assertFalse(stripped.matches(".*[<>:\"/\\\\|?*].*"), "Illegal FS characters remained: " + stripped);
    }

    @Test
    void rejectsNullBlankAndDotOnlyNames() {
        assertEquals("", SharedWorldManager.sanitizeName(null));
        assertEquals("", SharedWorldManager.sanitizeName(""));
        assertEquals("", SharedWorldManager.sanitizeName("   "));
        assertEquals("", SharedWorldManager.sanitizeName("."));
        assertEquals("", SharedWorldManager.sanitizeName(".."));
        assertEquals("", SharedWorldManager.sanitizeName("..."));
        assertEquals("", SharedWorldManager.sanitizeName(" ... "));
    }

    @Test
    void protectsAgainstWindowsReservedDeviceNames() {
        for (String reserved : new String[]{"CON", "con", "PRN", "AUX", "nul", "NUL", "COM1", "com9", "LPT1", "lpt9", "CON.txt"}) {
            String sanitized = SharedWorldManager.sanitizeName(reserved);
            assertFalse(sanitized.isEmpty(), "Sanitized reserved name must not be empty for: " + reserved);
            assertFalse(WINDOWS_RESERVED_DEVICE.matcher(sanitized).matches(),
                    "Sanitized result must not be a raw Windows reserved device name: " + sanitized);
        }
    }

    @Test
    void localPullFailsAndSkipsPruningOnSha256Mismatch(@TempDir Path tempDir) throws Exception {
        Path syncRoot = tempDir.resolve("sync");
        Path sharedWorldDir = syncRoot.resolve("TestWorld");
        Path filesDir = sharedWorldDir.resolve("files");
        Files.createDirectories(filesDir);

        Files.writeString(filesDir.resolve("level.dat"), "corrupted-content", StandardCharsets.UTF_8);
        String manifestJson = """
                {
                  "revision": 2,
                  "lastHost": "Alice",
                  "updatedAtMs": 1700000000000,
                  "files": {
                    "level.dat": {
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                      "size": 17
                    }
                  }
                }
                """;
        Files.writeString(sharedWorldDir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);

        Path targetWorldDir = tempDir.resolve("saves").resolve("TestWorld");
        Files.createDirectories(targetWorldDir);
        Path preExistingLevelDat = targetWorldDir.resolve("level.dat");
        Files.writeString(preExistingLevelDat, "valid-local-world", StandardCharsets.UTF_8);
        Path preExistingExtra = targetWorldDir.resolve("keep_me.dat");
        Files.writeString(preExistingExtra, "do-not-prune", StandardCharsets.UTF_8);

        SharedWorldManager.SyncOpResult result = SharedWorldManager.getInstance()
                .pullIncrementalFromLocalFolder(syncRoot.toString(), "TestWorld", targetWorldDir);

        assertFalse(result.success(), "Pull must fail when copied file SHA-256 does not match manifest");
        assertTrue(result.message().contains("SHA-256"), "Error message should mention SHA-256: " + result.message());
        assertEquals("valid-local-world", Files.readString(preExistingLevelDat, StandardCharsets.UTF_8),
                "Staged pull must not overwrite valid local files when SHA-256 verification fails");
        assertTrue(Files.exists(preExistingExtra), "Incomplete pull must not prune existing local files");
    }

    @Test
    void localPullRejectsEmptyManifestWithoutDeletingWorldFiles(@TempDir Path tempDir) throws Exception {
        Path syncRoot = tempDir.resolve("sync");
        Path sharedWorldDir = syncRoot.resolve("EmptyManifestWorld");
        Files.createDirectories(sharedWorldDir.resolve("files"));
        Files.writeString(sharedWorldDir.resolve("manifest.json"), """
                {
                  "revision": 4,
                  "lastHost": "Alice",
                  "updatedAtMs": 1700000000000,
                  "files": {}
                }
                """, StandardCharsets.UTF_8);

        Path targetWorldDir = tempDir.resolve("saves").resolve("EmptyManifestWorld");
        Files.createDirectories(targetWorldDir);
        Path levelDat = targetWorldDir.resolve("level.dat");
        Files.writeString(levelDat, "keep-world-safe", StandardCharsets.UTF_8);

        SharedWorldManager.SyncOpResult result = SharedWorldManager.getInstance()
                .pullIncrementalFromLocalFolder(syncRoot.toString(), "EmptyManifestWorld", targetWorldDir);

        assertFalse(result.success(), "Empty manifest must be rejected");
        assertTrue(Files.exists(levelDat), "Empty manifest must not delete existing world files");
    }

    @Test
    void localPullFailsAndSkipsPruningOnMissingFile(@TempDir Path tempDir) throws Exception {
        Path syncRoot = tempDir.resolve("sync");
        Path sharedWorldDir = syncRoot.resolve("MissingWorld");
        Files.createDirectories(sharedWorldDir.resolve("files"));

        String manifestJson = """
                {
                  "revision": 3,
                  "lastHost": "Bob",
                  "updatedAtMs": 1700000000000,
                  "files": {
                    "region/r.0.0.mca": {
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "size": 100
                    }
                  }
                }
                """;
        Files.writeString(sharedWorldDir.resolve("manifest.json"), manifestJson, StandardCharsets.UTF_8);

        Path targetWorldDir = tempDir.resolve("saves").resolve("MissingWorld");
        Files.createDirectories(targetWorldDir);
        Path preExistingExtra = targetWorldDir.resolve("keep_me.dat");
        Files.writeString(preExistingExtra, "do-not-prune", StandardCharsets.UTF_8);

        SharedWorldManager.SyncOpResult result = SharedWorldManager.getInstance()
                .pullIncrementalFromLocalFolder(syncRoot.toString(), "MissingWorld", targetWorldDir);

        assertFalse(result.success(), "Pull must fail when a manifest file is missing from sync folder");
        assertTrue(result.message().contains("missing"), "Error message should mention missing files: " + result.message());
        assertTrue(Files.exists(preExistingExtra), "Incomplete pull must not prune existing local files");
    }
}
