package com.slyph.cloverchat.integration.cloverbadges;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CloverBadgesMessageColorBridge {
    private static final String ID_PLACEHOLDER = "%cloverbadges_message_color_id%";
    private static final String GRADIENT_PLACEHOLDER = "%cloverbadges_message_color_gradient%";
    private static final String FORMAT_PLACEHOLDER = "%cloverbadges_message_color_format%";

    private final CloverChatPlugin plugin;

    public CloverBadgesMessageColorBridge(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public Component apply(Player player, Component message) {
        if (player == null || message == null || !plugin.isPlaceholderApiHooked()) {
            return message;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("CloverBadges")) {
            return message;
        }

        String colorId = resolve(player, ID_PLACEHOLDER);
        if (colorId.isBlank()) {
            return message;
        }

        List<Integer> gradient = parseGradient(resolve(player, GRADIENT_PLACEHOLDER));
        if (gradient.size() >= 2) {
            int totalLength = countCodePoints(message);
            if (totalLength > 0) {
                return applyGradient(message, gradient, totalLength, new int[]{0});
            }
            return message;
        }

        Integer solidColor = parseColor(resolve(player, FORMAT_PLACEHOLDER));
        if (solidColor == null) {
            return message;
        }
        return applySolid(message, TextColor.color(solidColor));
    }

    private String resolve(Player player, String placeholder) {
        String value = plugin.applyPlaceholders(player, placeholder);
        if (value == null || value.equals(placeholder) || value.contains("%cloverbadges_")) {
            return "";
        }
        return value.trim();
    }

    private List<Integer> parseGradient(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<Integer> colors = new ArrayList<>();
        for (String part : input.split(",")) {
            Integer color = parseColor(part);
            if (color != null) {
                colors.add(color);
            }
        }
        return List.copyOf(colors);
    }

    private Integer parseColor(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("&") || normalized.startsWith("§")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("(?i)[0-9a-f]{6}")) {
            return Integer.parseInt(normalized, 16);
        }
        if (normalized.matches("(?i)[0-9a-f]")) {
            return legacyColor(normalized.charAt(0));
        }
        return namedColor(normalized);
    }

    private int legacyColor(char code) {
        switch (Character.toLowerCase(code)) {
            case '0':
                return 0x000000;
            case '1':
                return 0x0000AA;
            case '2':
                return 0x00AA00;
            case '3':
                return 0x00AAAA;
            case '4':
                return 0xAA0000;
            case '5':
                return 0xAA00AA;
            case '6':
                return 0xFFAA00;
            case '7':
                return 0xAAAAAA;
            case '8':
                return 0x555555;
            case '9':
                return 0x5555FF;
            case 'a':
                return 0x55FF55;
            case 'b':
                return 0x55FFFF;
            case 'c':
                return 0xFF5555;
            case 'd':
                return 0xFF55FF;
            case 'e':
                return 0xFFFF55;
            default:
                return 0xFFFFFF;
        }
    }

    private Integer namedColor(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (normalized) {
            case "black":
                return 0x000000;
            case "dark_blue":
                return 0x0000AA;
            case "dark_green":
                return 0x00AA00;
            case "dark_aqua":
                return 0x00AAAA;
            case "dark_red":
                return 0xAA0000;
            case "dark_purple":
                return 0xAA00AA;
            case "gold":
                return 0xFFAA00;
            case "gray":
            case "grey":
                return 0xAAAAAA;
            case "dark_gray":
            case "dark_grey":
                return 0x555555;
            case "blue":
                return 0x5555FF;
            case "green":
                return 0x55FF55;
            case "aqua":
                return 0x55FFFF;
            case "red":
                return 0xFF5555;
            case "light_purple":
                return 0xFF55FF;
            case "yellow":
                return 0xFFFF55;
            case "white":
                return 0xFFFFFF;
            default:
                return null;
        }
    }

    private int countCodePoints(Component component) {
        int length = 0;
        if (component instanceof TextComponent) {
            String content = ((TextComponent) component).content();
            length += content.codePointCount(0, content.length());
        }
        for (Component child : component.children()) {
            length += countCodePoints(child);
        }
        return length;
    }

    private Component applyGradient(Component component, List<Integer> colors, int totalLength, int[] position) {
        List<Component> originalChildren = component.children();
        Component result = component.children(List.of());

        if (component instanceof TextComponent) {
            String content = ((TextComponent) component).content();
            result = ((TextComponent) result).content("");
            int offset = 0;
            while (offset < content.length()) {
                int codePoint = content.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                int color = interpolate(colors, position[0], totalLength);
                result = result.append(Component.text(character, TextColor.color(color)));
                position[0]++;
                offset += Character.charCount(codePoint);
            }
        }

        for (Component child : originalChildren) {
            result = result.append(applyGradient(child, colors, totalLength, position));
        }
        return result;
    }

    private Component applySolid(Component component, TextColor color) {
        List<Component> originalChildren = component.children();
        Component result = component.children(List.of()).color(color);
        for (Component child : originalChildren) {
            result = result.append(applySolid(child, color));
        }
        return result;
    }

    private int interpolate(List<Integer> colors, int position, int totalLength) {
        if (colors.size() == 1 || totalLength <= 1) {
            return colors.get(0);
        }
        double progress = Math.max(0.0D, Math.min(1.0D, (double) position / (double) (totalLength - 1)));
        double scaled = progress * (colors.size() - 1);
        int segment = Math.min(colors.size() - 2, (int) Math.floor(scaled));
        double localProgress = scaled - segment;
        int start = colors.get(segment);
        int end = colors.get(segment + 1);
        int red = lerp((start >> 16) & 0xFF, (end >> 16) & 0xFF, localProgress);
        int green = lerp((start >> 8) & 0xFF, (end >> 8) & 0xFF, localProgress);
        int blue = lerp(start & 0xFF, end & 0xFF, localProgress);
        return (red << 16) | (green << 8) | blue;
    }

    private int lerp(int start, int end, double progress) {
        return (int) Math.round(start + (end - start) * progress);
    }
}
