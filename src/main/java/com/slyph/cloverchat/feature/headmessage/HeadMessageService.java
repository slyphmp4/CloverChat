package com.slyph.cloverchat.feature.headmessage;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HeadMessageService {

    private final CloverChatPlugin plugin;
    private final Map<UUID, ActiveHeadMessage> activeMessages = new HashMap<>();

    public HeadMessageService(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!plugin.configuration().getBoolean("chat-above-head.enabled", true)) {
            return;
        }

        String source = message == null ? "" : message.trim();
        if (source.isEmpty()) {
            return;
        }

        int maxLength = Math.max(1, plugin.configuration().getInt("chat-above-head.max-length", 72));
        String prepared = source.length() > maxLength && maxLength > 3
                ? source.substring(0, maxLength - 3) + "..."
                : source;

        long durationSeconds = Math.max(1L, plugin.configuration().getLong("chat-above-head.duration-seconds", 4L));
        double yOffset = plugin.configuration().getDouble("chat-above-head.y-offset", 2.25D);
        long now = System.currentTimeMillis();
        long expiresAt = now + durationSeconds * 1000L;

        ActiveHeadMessage active = activeMessages.get(player.getUniqueId());
        if (active != null && active.isAlive(player) && now <= active.expiresAtMillis) {
            active.comboCount += 1;
            active.expiresAtMillis = expiresAt;
            active.yOffset = yOffset;
            active.stand.setCustomName(buildRendered(player, prepared, active.comboCount));
            return;
        }

        clear(player.getUniqueId());

        Location spawnLocation = player.getLocation().add(0.0D, yOffset, 0.0D);
        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(spawnLocation, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setInvulnerable(true);
        stand.setCustomNameVisible(true);
        stand.setSilent(true);
        stand.setCollidable(false);
        stand.setPersistent(false);

        ActiveHeadMessage created = new ActiveHeadMessage(stand);
        created.comboCount = 1;
        created.expiresAtMillis = expiresAt;
        created.yOffset = yOffset;
        stand.setCustomName(buildRendered(player, prepared, created.comboCount));

        BukkitTask followTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!created.isAlive(player)) {
                    clear(player.getUniqueId());
                    return;
                }

                if (System.currentTimeMillis() >= created.expiresAtMillis) {
                    clear(player.getUniqueId());
                    return;
                }

                if (!created.stand.getWorld().equals(player.getWorld())) {
                    clear(player.getUniqueId());
                    return;
                }

                created.stand.teleport(player.getLocation().add(0.0D, created.yOffset, 0.0D));
            }
        }.runTaskTimer(plugin, 0L, 2L);

        created.task = followTask;
        activeMessages.put(player.getUniqueId(), created);
    }

    public void clear(UUID playerId) {
        ActiveHeadMessage active = activeMessages.remove(playerId);
        if (active == null) {
            return;
        }

        if (active.task != null) {
            active.task.cancel();
        }
        if (active.stand.isValid()) {
            active.stand.remove();
        }
    }

    public void clearAll() {
        for (UUID playerId : new ArrayList<>(activeMessages.keySet())) {
            clear(playerId);
        }
    }

    private static final class ActiveHeadMessage {

        private final ArmorStand stand;
        private BukkitTask task;
        private int comboCount;
        private long expiresAtMillis;
        private double yOffset;

        private ActiveHeadMessage(ArmorStand stand) {
            this.stand = stand;
        }

        private boolean isAlive(Player player) {
            return player != null
                    && player.isOnline()
                    && !player.isDead()
                    && stand.isValid()
                    && stand.getWorld().equals(player.getWorld());
        }
    }

    private String buildRendered(Player player, String message, int comboCount) {
        String format = plugin.configuration().getString("chat-above-head.format", "&#8fe8ff%message%");
        String rendered = format
                .replace("%message%", message)
                .replace("%player_name%", player.getName());

        if (plugin.configuration().getBoolean("chat-above-head.combo-enabled", true) && comboCount > 1) {
            int comboExtra = comboCount - 1;
            String comboFormat = plugin.configuration().getString("chat-above-head.combo-format", " &#9fff9f(+%combo%)");
            String suffix = comboFormat
                    .replace("%combo%", String.valueOf(comboExtra))
                    .replace("%combo_total%", String.valueOf(comboCount));
            rendered = rendered + suffix;
        }

        rendered = plugin.applyPlaceholders(player, rendered);
        return plugin.applyColor(rendered);
    }
}
