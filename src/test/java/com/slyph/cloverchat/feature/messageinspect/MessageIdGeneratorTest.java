package com.slyph.cloverchat.feature.messageinspect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MessageIdGeneratorTest {

    @Test
    void createsUniqueServerScopedIds() {
        String first = MessageIdGenerator.create("survival");
        String second = MessageIdGenerator.create("survival");

        assertNotEquals(first, second);
        assertTrue(first.matches("survival-[0-9A-F-]{36}"));
        assertTrue(first.length() <= 80);
    }

    @Test
    void sanitizesUnsafeServerNames() {
        assertEquals("server_one", MessageIdGenerator.sanitizeServerId("server one"));
        assertEquals("server", MessageIdGenerator.sanitizeServerId("   "));
        assertEquals(24, MessageIdGenerator.sanitizeServerId("abcdefghijklmnopqrstuvwxyz").length());
    }
}
