package com.slyph.cloverchat.feature.messagestyle;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MessageStyleService {

    private final CloverChatPlugin plugin;
    private volatile boolean enabled;
    private volatile MessageStyle fallbackStyle;
    private volatile List<MessageStyleRule> rules = List.of();

    public MessageStyleService(CloverChatPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection root = plugin.configuration().getConfigurationSection("message-styles");
        if (root == null) {
            enabled = false;
            fallbackStyle = null;
            rules = List.of();
            return;
        }

        enabled = root.getBoolean("enabled", true);
        fallbackStyle = readFallbackStyle(root.getConfigurationSection("fallback"));
        rules = readRules(root.getConfigurationSection("rules"));
    }

    public Component apply(Player player, String ultraPermissionsGroup, Component message) {
        if (!enabled || player == null || message == null) {
            return message;
        }

        for (MessageStyleRule rule : rules) {
            if (rule.matches(player, ultraPermissionsGroup)) {
                return rule.style.apply(message);
            }
        }

        if (fallbackStyle != null) {
            return fallbackStyle.apply(message);
        }
        return message;
    }

    private MessageStyle readFallbackStyle(ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return null;
        }
        return readStyle("fallback", section);
    }

    private List<MessageStyleRule> readRules(ConfigurationSection section) {
        if (section == null) {
            return List.of();
        }

        List<MessageStyleRule> loadedRules = new ArrayList<>();
        for (String ruleKey : section.getKeys(false)) {
            ConfigurationSection ruleSection = section.getConfigurationSection(ruleKey);
            if (ruleSection == null || !ruleSection.getBoolean("enabled", true)) {
                continue;
            }

            ConfigurationSection selectorSection = ruleSection.getConfigurationSection("selector");
            ConfigurationSection styleSection = ruleSection.getConfigurationSection("style");
            if (selectorSection == null || styleSection == null) {
                plugin.getLogger().warning("Message style rule '" + ruleKey + "' requires selector and style sections");
                continue;
            }

            SelectorType selectorType = SelectorType.from(selectorSection.getString("type", ""));
            String selectorValue = selectorSection.getString("value", "").trim();
            if (selectorType == null || selectorValue.isEmpty()) {
                plugin.getLogger().warning("Message style rule '" + ruleKey + "' has an invalid selector");
                continue;
            }

            MessageStyle style = readStyle(ruleKey, styleSection);
            loadedRules.add(new MessageStyleRule(
                    ruleSection.getInt("priority", 0),
                    selectorType,
                    selectorValue,
                    style
            ));
        }

        loadedRules.sort(Comparator.comparingInt((MessageStyleRule rule) -> rule.priority).reversed());
        return List.copyOf(loadedRules);
    }

    private MessageStyle readStyle(String ruleKey, ConfigurationSection section) {
        String rawColor = section.getString("color", "").trim();
        Integer colorValue = MessageStyleColorParser.parse(rawColor);
        TextColor color = colorValue == null ? null : TextColor.color(colorValue);
        if (colorValue == null && !rawColor.isEmpty() && !rawColor.equalsIgnoreCase("none")) {
            plugin.getLogger().warning("Message style rule '" + ruleKey + "' has an invalid color: " + rawColor);
        }

        return new MessageStyle(
                color,
                section.getBoolean("bold", false),
                section.getBoolean("italic", false),
                section.getBoolean("underlined", false),
                section.getBoolean("strikethrough", false)
        );
    }

    private enum SelectorType {
        PERMISSION,
        ULTRAPERMISSIONS_GROUP;

        private static SelectorType from(String input) {
            if (input == null) {
                return null;
            }
            String normalized = input.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (normalized.equals("GROUP") || normalized.equals("ULTRAPERMISSIONS")) {
                return ULTRAPERMISSIONS_GROUP;
            }
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private static final class MessageStyleRule {

        private final int priority;
        private final SelectorType selectorType;
        private final String selectorValue;
        private final MessageStyle style;

        private MessageStyleRule(int priority, SelectorType selectorType, String selectorValue, MessageStyle style) {
            this.priority = priority;
            this.selectorType = selectorType;
            this.selectorValue = selectorValue;
            this.style = style;
        }

        private boolean matches(Player player, String ultraPermissionsGroup) {
            if (selectorType == SelectorType.PERMISSION) {
                return player.hasPermission(selectorValue);
            }
            String group = ultraPermissionsGroup == null ? "" : ultraPermissionsGroup.trim();
            return !group.isEmpty() && group.equalsIgnoreCase(selectorValue);
        }
    }

    private static final class MessageStyle {

        private final TextColor color;
        private final boolean bold;
        private final boolean italic;
        private final boolean underlined;
        private final boolean strikethrough;

        private MessageStyle(TextColor color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
        }

        private Component apply(Component component) {
            Component styled = color == null ? component : component.color(color);
            return styled
                    .decoration(TextDecoration.BOLD, bold)
                    .decoration(TextDecoration.ITALIC, italic)
                    .decoration(TextDecoration.UNDERLINED, underlined)
                    .decoration(TextDecoration.STRIKETHROUGH, strikethrough);
        }
    }
}
