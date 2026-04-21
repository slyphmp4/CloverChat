package com.slyph.cloverchat.feature.automessage;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class AutoMessageService {

    private final CloverChatPlugin plugin;
    private final Random random = new Random();
    private BukkitTask task;
    private int nextMessageIndex;

    public AutoMessageService(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        FileConfiguration configuration = plugin.autoMessages();
        if (configuration == null || !configuration.getBoolean("enabled", false)) {
            return;
        }

        long intervalSeconds = Math.max(5L, configuration.getLong("interval-seconds", 180L));
        long firstDelaySeconds = Math.max(0L, configuration.getLong("first-delay-seconds", 20L));
        long intervalTicks = intervalSeconds * 20L;
        long firstDelayTicks = firstDelaySeconds * 20L;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastNextMessage, firstDelayTicks, intervalTicks);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nextMessageIndex = 0;
    }

    private void broadcastNextMessage() {
        FileConfiguration configuration = plugin.autoMessages();
        if (configuration == null || !configuration.getBoolean("enabled", false)) {
            return;
        }

        if (configuration.getBoolean("require-players-online", true) && Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        List<AutoMessageEntry> entries = readEntries(configuration.getMapList("messages"));
        if (entries.isEmpty()) {
            return;
        }

        AutoMessageEntry entry = selectEntry(entries, configuration.getBoolean("random-order", false));
        if (entry == null || entry.lines.isEmpty()) {
            return;
        }

        int onlineCount = Bukkit.getOnlinePlayers().size();
        if (onlineCount < entry.minOnline) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!entry.permission.isEmpty() && !player.hasPermission(entry.permission)) {
                continue;
            }
            plugin.sendConfiguredLines(player, player, entry.lines);
        }
    }

    private AutoMessageEntry selectEntry(List<AutoMessageEntry> entries, boolean randomOrder) {
        if (entries.isEmpty()) {
            return null;
        }

        if (randomOrder) {
            return entries.get(random.nextInt(entries.size()));
        }

        if (nextMessageIndex >= entries.size()) {
            nextMessageIndex = 0;
        }

        AutoMessageEntry entry = entries.get(nextMessageIndex);
        nextMessageIndex++;
        if (nextMessageIndex >= entries.size()) {
            nextMessageIndex = 0;
        }
        return entry;
    }

    private List<AutoMessageEntry> readEntries(List<Map<?, ?>> mapList) {
        List<AutoMessageEntry> entries = new ArrayList<>();
        if (mapList == null || mapList.isEmpty()) {
            return entries;
        }

        for (Map<?, ?> map : mapList) {
            if (map == null || map.isEmpty()) {
                continue;
            }

            String permission = stringValue(map.get("permission"));
            int minOnline = intValue(map.get("min-online"), 0);
            List<String> lines = listValue(map.get("lines"));
            if (lines.isEmpty()) {
                continue;
            }

            entries.add(new AutoMessageEntry(permission, minOnline, lines));
        }

        return entries;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<String> listValue(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        List<?> list = (List<?>) value;
        for (Object element : list) {
            if (element == null) {
                continue;
            }
            result.add(String.valueOf(element));
        }
        return result;
    }

    private static final class AutoMessageEntry {

        private final String permission;
        private final int minOnline;
        private final List<String> lines;

        private AutoMessageEntry(String permission, int minOnline, List<String> lines) {
            this.permission = permission == null ? "" : permission;
            this.minOnline = Math.max(0, minOnline);
            this.lines = lines;
        }
    }
}
