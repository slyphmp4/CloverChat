package com.slyph.cloverchat.feature.messageinspect;

import java.util.Locale;
import java.util.UUID;

public final class MessageIdGenerator {

    private MessageIdGenerator() {
    }

    public static String create(String serverId) {
        return sanitizeServerId(serverId) + "-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    }

    public static String sanitizeServerId(String value) {
        String source = value == null ? "" : value.trim();
        String sanitized = source.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (sanitized.isBlank()) {
            return "server";
        }
        if (sanitized.length() > 24) {
            return sanitized.substring(0, 24);
        }
        return sanitized;
    }
}
