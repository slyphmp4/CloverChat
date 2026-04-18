package com.slyph.cloverchat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("(?i)&(?:#)?([A-Fa-f0-9]{6})");

    private ColorUtil() {
    }

    public static String colorize(String input, boolean hexEnabled) {
        if (input == null) {
            return "";
        }
        String text = hexEnabled ? applyHexColors(input) : input;
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static Component toComponent(String input, boolean hexEnabled) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacySection().deserialize(colorize(input, hexEnabled));
    }

    private static String applyHexColors(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00A7x");
            for (char c : hex.toCharArray()) {
                replacement.append('\u00A7').append(c);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
