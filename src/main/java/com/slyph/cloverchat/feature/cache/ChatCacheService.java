package com.slyph.cloverchat.feature.cache;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.util.ColorUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatCacheService {

    private final CloverChatPlugin plugin;
    private volatile State state;

    public ChatCacheService(CloverChatPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.configuration();
        boolean enabled = config.getBoolean("chat-cache.enabled", true);
        long formatsTtl = ttl(config, "formats", enabled, 60000);
        long hoversTtl = ttl(config, "hovers", enabled, 60000);
        long papiTtl = ttl(config, "placeholderapi", enabled, 1000);
        boolean papiEnabled = enabled && config.getBoolean("chat-cache.placeholderapi.enabled", true);
        Map<String, PlaceholderValueCache.Rule> rules = new HashMap<>();
        for (Map<?, ?> entry : config.getMapList("chat-cache.placeholderapi.rules")) {
            String placeholder = String.valueOf(entry.get("placeholder"));
            if (!placeholder.matches("%[^%\\r\\n]+%")) {
                plugin.getLogger().warning("[Cache] Invalid placeholder rule skipped");
                continue;
            }
            long ruleTtl = entry.containsKey("ttl-millis")
                    ? number(entry.get("ttl-millis"), papiTtl) : papiTtl;
            Map<String, String> replacements = new HashMap<>();
            Object rawReplacements = entry.get("replacements");
            if (rawReplacements instanceof List) {
                for (Object raw : (List<?>) rawReplacements) {
                    if (raw instanceof Map) {
                        Map<?, ?> replacement = (Map<?, ?>) raw;
                        if (replacement.containsKey("from") && replacement.containsKey("to")) {
                            replacements.put(String.valueOf(replacement.get("from")), String.valueOf(replacement.get("to")));
                        }
                    }
                }
            }
            rules.put(placeholder, new PlaceholderValueCache.Rule(ruleTtl, replacements));
        }
        state = new State(formatsTtl, hoversTtl,
                limit(config, "formats"), limit(config, "hovers"),
                papiEnabled ? new PlaceholderValueCache(limit(config, "placeholderapi"), papiTtl, rules) : null);
    }

    public String format(String path, String fallback) {
        State current = state;
        return current.formats.get("template:" + path, current.formatsTtl,
                () -> plugin.messages().getString(path, fallback));
    }

    public List<String> hoverLines(String path) {
        State current = state;
        return current.hoverTemplates.get(path, current.hoversTtl,
                () -> List.copyOf(plugin.hovers().getStringList(path)));
    }

    public Component formatComponent(String text) {
        State current = state;
        return current.formatComponents.get(text, current.formatsTtl,
                () -> ColorUtil.toComponent(text, plugin.configuration().getBoolean("hex-colors", true)));
    }

    public Component hoverComponent(String text) {
        State current = state;
        return current.hoverComponents.get(text, current.hoversTtl,
                () -> ColorUtil.toComponent(text, plugin.configuration().getBoolean("hex-colors", true)));
    }

    public String placeholders(Player player, String text) {
        State current = state;
        if (current.papi == null) {
            return PlaceholderAPI.setPlaceholders(player, text);
        }
        return current.papi.resolve(player.getUniqueId(), text,
                placeholder -> PlaceholderAPI.setPlaceholders(player, placeholder));
    }

    public void invalidatePlayer(UUID playerId) {
        State current = state;
        if (current.papi != null) {
            current.papi.invalidatePlayer(playerId);
        }
    }

    private long ttl(FileConfiguration config, String name, boolean enabled, long fallback) {
        return enabled && config.getBoolean("chat-cache." + name + ".enabled", true)
                ? number(config.get("chat-cache." + name + ".ttl-millis"), fallback) : 0;
    }

    private int limit(FileConfiguration config, String name) {
        return Math.max(16, Math.min(100000, config.getInt("chat-cache." + name + ".max-entries", 4096)));
    }

    private long number(Object value, long fallback) {
        try {
            return Math.max(0, Math.min(86400000, Long.parseLong(String.valueOf(value))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static final class State {
        private final long formatsTtl;
        private final long hoversTtl;
        private final BoundedTtlCache<String, String> formats;
        private final BoundedTtlCache<String, Component> formatComponents;
        private final BoundedTtlCache<String, List<String>> hoverTemplates;
        private final BoundedTtlCache<String, Component> hoverComponents;
        private final PlaceholderValueCache papi;

        private State(long formatsTtl, long hoversTtl, int formatsLimit, int hoversLimit, PlaceholderValueCache papi) {
            this.formatsTtl = formatsTtl;
            this.hoversTtl = hoversTtl;
            formats = new BoundedTtlCache<>(formatsLimit);
            formatComponents = new BoundedTtlCache<>(formatsLimit);
            hoverTemplates = new BoundedTtlCache<>(hoversLimit);
            hoverComponents = new BoundedTtlCache<>(hoversLimit);
            this.papi = papi;
        }
    }
}
