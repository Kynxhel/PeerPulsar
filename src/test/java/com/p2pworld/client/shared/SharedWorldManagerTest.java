package com.p2pworld.client.shared;

import org.junit.jupiter.api.Test;

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
}
