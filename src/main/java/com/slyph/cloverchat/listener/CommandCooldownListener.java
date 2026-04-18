package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CommandCooldownListener implements Listener {

    private final CloverChatPlugin plugin;
    private final Map<UUID, Long> lastCommandTime = new HashMap<>();

    public CommandCooldownListener(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!plugin.configuration().getBoolean("commands-cooldown.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();

        if (player.hasPermission("cloverchat.commandcooldown.bypass")) {
            return;
        }

        String baseCommand = extractBaseCommand(event.getMessage());
        if (baseCommand.isEmpty() || baseCommand.equals("m")) {
            return;
        }

        long cooldownSeconds = plugin.configuration().getLong("commands-cooldown.seconds", 5);
        long cooldownMillis = cooldownSeconds * 1000L;

        long now = System.currentTimeMillis();
        Long last = lastCommandTime.get(player.getUniqueId());

        if (last != null) {
            long diff = now - last;
            if (diff < cooldownMillis) {
                long remain = Math.max(1, (cooldownMillis - diff + 999) / 1000);
                List<String> lines = plugin.messages().getStringList("commands-cooldown.message");
                if (lines.isEmpty()) {
                    lines = Arrays.asList("&7", "&cПодождите %remain% сек. перед следующей командой", "&7");
                }

                for (String line : lines) {
                    String resolved = line.replace("%remain%", String.valueOf(remain));
                    player.sendMessage(plugin.deserializeColored(plugin.applyPlaceholders(player, resolved)));
                }

                event.setCancelled(true);
                return;
            }
        }

        lastCommandTime.put(player.getUniqueId(), now);
    }

    private String extractBaseCommand(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith("/")) {
            return "";
        }

        String trimmed = raw.substring(1).trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }

        return trimmed.substring(0, space).toLowerCase(Locale.ROOT);
    }
}
