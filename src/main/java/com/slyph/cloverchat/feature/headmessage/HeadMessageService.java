package com.slyph.cloverchat.feature.headmessage;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.util.CompatScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeadMessageService {

    private final CloverChatPlugin plugin;
    private final Map<UUID, ActiveHeadMessage> activeMessages = new ConcurrentHashMap<>();
    private volatile Method teleportAsyncMethod;
    private volatile boolean teleportAsyncResolved;

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

        UUID playerId = player.getUniqueId();
        plugin.scheduler().runEntity(player, () -> showInternal(playerId, source));
    }

    private void showInternal(UUID playerId, String source) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead()) {
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

        ActiveHeadMessage active = activeMessages.get(playerId);
        if (active != null && now <= active.expiresAtMillis) {
            active.comboCount += 1;
            active.expiresAtMillis = expiresAt;
            active.yOffset = yOffset;
            String rendered = buildRendered(player, prepared, active.comboCount);
            updateStand(playerId, player, active, prepared, rendered);
            return;
        }

        clear(playerId);

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
        activeMessages.put(playerId, created);

        CompatScheduler.TaskHandle followTask = plugin.scheduler().runEntityRepeating(player, 1L, 2L, () -> {
            if (!player.isOnline() || player.isDead() || System.currentTimeMillis() >= created.expiresAtMillis) {
                clear(playerId);
                return;
            }
            if (activeMessages.get(playerId) != created) {
                if (created.task != null) {
                    created.task.cancel();
                }
                return;
            }
            moveStand(playerId, created, player.getLocation().add(0.0D, created.yOffset, 0.0D));
        });

        created.task = followTask;
        if (followTask == null) {
            activeMessages.remove(playerId, created);
            stand.remove();
        }
    }

    private void updateStand(
            UUID playerId,
            Player player,
            ActiveHeadMessage active,
            String prepared,
            String rendered
    ) {
        CompatScheduler.TaskHandle handle = plugin.scheduler().runEntity(active.stand, () -> {
            if (activeMessages.get(playerId) != active) {
                return;
            }
            if (active.stand.isValid()) {
                active.stand.setCustomName(rendered);
                return;
            }
            if (activeMessages.remove(playerId, active) && active.task != null) {
                active.task.cancel();
            }
            plugin.scheduler().runEntity(player, () -> showInternal(playerId, prepared));
        });
        if (handle == null) {
            activeMessages.remove(playerId, active);
            if (active.task != null) {
                active.task.cancel();
            }
        }
    }

    private void moveStand(UUID playerId, ActiveHeadMessage active, Location destination) {
        plugin.scheduler().runEntity(active.stand, () -> {
            if (activeMessages.get(playerId) != active || !active.stand.isValid()) {
                return;
            }
            if (!plugin.scheduler().isFolia()) {
                active.stand.teleport(destination);
                return;
            }
            if (!teleportAsync(active.stand, destination)) {
                activeMessages.remove(playerId, active);
                if (active.task != null) {
                    active.task.cancel();
                }
                active.stand.remove();
            }
        });
    }

    private boolean teleportAsync(ArmorStand stand, Location destination) {
        Method method = teleportAsyncMethod;
        if (!teleportAsyncResolved) {
            synchronized (this) {
                if (!teleportAsyncResolved) {
                    try {
                        teleportAsyncMethod = stand.getClass().getMethod("teleportAsync", Location.class);
                    } catch (Exception ignored) {
                        teleportAsyncMethod = null;
                    }
                    teleportAsyncResolved = true;
                }
                method = teleportAsyncMethod;
            }
        }
        if (method == null) {
            return false;
        }
        try {
            method.invoke(stand, destination);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void clear(UUID playerId) {
        ActiveHeadMessage active = activeMessages.remove(playerId);
        if (active == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        plugin.scheduler().runEntity(active.stand, () -> {
            if (active.stand.isValid()) {
                active.stand.remove();
            }
        });
    }

    public void clearAll() {
        for (UUID playerId : new ArrayList<>(activeMessages.keySet())) {
            clear(playerId);
        }
    }

    private String buildRendered(Player player, String message, int comboCount) {
        String format = plugin.messages().getString("chat-above-head.format", "&#8fe8ff%message%");
        String messageToken = "__cloverchat_head_message__";
        String rendered = (format == null ? "" : format)
                .replace("%message%", messageToken)
                .replace("%player_name%", player.getName());

        if (plugin.configuration().getBoolean("chat-above-head.combo-enabled", true) && comboCount > 1) {
            int comboExtra = comboCount - 1;
            String comboFormat = plugin.messages().getString("chat-above-head.combo-format", " &#9fff9f(+%combo%)");
            String suffix = (comboFormat == null ? "" : comboFormat)
                    .replace("%combo%", String.valueOf(comboExtra))
                    .replace("%combo_total%", String.valueOf(comboCount));
            rendered = rendered + suffix;
        }

        rendered = plugin.applyPlaceholders(player, rendered);
        rendered = rendered.replace(messageToken, message);
        return plugin.applyColor(rendered);
    }

    private static final class ActiveHeadMessage {

        private final ArmorStand stand;
        private volatile CompatScheduler.TaskHandle task;
        private volatile int comboCount;
        private volatile long expiresAtMillis;
        private volatile double yOffset;

        private ActiveHeadMessage(ArmorStand stand) {
            this.stand = stand;
        }
    }
}
