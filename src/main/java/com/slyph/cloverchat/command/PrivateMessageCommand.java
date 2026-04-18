package com.slyph.cloverchat.command;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PrivateMessageCommand implements CommandExecutor {

    private final CloverChatPlugin plugin;
    private final Map<UUID, Long> lastPrivateMessageTime = new HashMap<>();

    public PrivateMessageCommand(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            List<String> nonPlayerLines = plugin.messages().getStringList("private-chat.non-player-message");
            if (nonPlayerLines.isEmpty()) {
                nonPlayerLines = Arrays.asList("&7", "&cЭта команда доступна только игрокам", "&7");
            }
            plugin.sendConfiguredLines(sender, null, nonPlayerLines);
            return true;
        }

        if (!player.hasPermission("cloverchat.pm")) {
            List<String> noPermissionLines = plugin.messages().getStringList("private-chat.no-permission-message");
            if (noPermissionLines.isEmpty()) {
                noPermissionLines = Arrays.asList("&7", "&cУ вас нет права на личные сообщения", "&7");
            }
            plugin.sendConfiguredLines(player, player, noPermissionLines);
            return true;
        }

        if (!plugin.configuration().getBoolean("private-chat.enabled", true)) {
            List<String> disabledLines = plugin.messages().getStringList("private-chat.disabled-message");
            if (disabledLines.isEmpty()) {
                disabledLines = Arrays.asList("&7", "&cЛичные сообщения отключены", "&7");
            }
            plugin.sendConfiguredLines(player, player, disabledLines);
            return true;
        }

        if (args.length < 2) {
            List<String> usageLines = plugin.messages().getStringList("private-chat.usage-message");
            if (usageLines.isEmpty()) {
                usageLines = Arrays.asList("&7", "&eИспользование: /m <ник> <сообщение>", "&7");
            }
            plugin.sendConfiguredLines(player, player, usageLines);
            return true;
        }

        boolean useCooldown = !player.hasPermission("cloverchat.pm.bypass.cooldown");
        long cooldownCheckTimestamp = System.currentTimeMillis();

        if (useCooldown) {
            long cooldownSeconds = plugin.configuration().getLong("private-chat.cooldown-seconds", 5);
            long cooldownMillis = cooldownSeconds * 1000L;
            Long lastTime = lastPrivateMessageTime.get(player.getUniqueId());

            if (lastTime != null) {
                long diff = cooldownCheckTimestamp - lastTime;
                if (diff < cooldownMillis) {
                    long remain = Math.max(1, (cooldownMillis - diff + 999) / 1000);
                    List<String> cooldownLines = plugin.messages().getStringList("private-chat.cooldown-message");
                    if (cooldownLines.isEmpty()) {
                        cooldownLines = Arrays.asList("&7", "&cПодождите %remain% сек. перед отправкой следующего ЛС", "&7");
                    }
                    for (String line : cooldownLines) {
                        String resolved = line.replace("%remain%", String.valueOf(remain));
                        player.sendMessage(plugin.deserializeColored(plugin.applyPlaceholders(player, resolved)));
                    }
                    return true;
                }
            }
        }

        String targetName = args[0];
        Player target = findOnlinePlayer(targetName);
        if (target == null) {
            List<String> offlineLines = plugin.messages().getStringList("private-chat.offline-player-message");
            if (offlineLines.isEmpty()) {
                offlineLines = Arrays.asList("&7", "&cИгрок %target_name% не в сети", "&7");
            }
            for (String line : offlineLines) {
                String resolved = line.replace("%target_name%", targetName);
                player.sendMessage(plugin.deserializeColored(plugin.applyPlaceholders(player, resolved)));
            }
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        String senderFormat = plugin.messages().getString(
                "private-chat.format-sender",
                "&7[&aВы &8→ &f%target_name%&7] &f%message%"
        );
        String receiverFormat = plugin.messages().getString(
                "private-chat.format-receiver",
                "&7[&f%player_name% &8→ &aВам&7] &f%message%"
        );

        senderFormat = senderFormat
                .replace("%target_name%", target.getName())
                .replace("%player_name%", player.getName())
                .replace("%message%", message);

        receiverFormat = receiverFormat
                .replace("%target_name%", target.getName())
                .replace("%player_name%", player.getName())
                .replace("%message%", message);

        senderFormat = plugin.applyPlaceholders(player, senderFormat);
        receiverFormat = plugin.applyPlaceholders(target, receiverFormat);

        player.sendMessage(plugin.deserializeColored(senderFormat));
        target.sendMessage(plugin.deserializeColored(receiverFormat));

        String soundKey = plugin.configuration().getString(
                "private-chat.notification-sound",
                "minecraft:block.note_block.pling"
        );

        try {
            target.playSound(Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, 1.0f, 1.0f));
        } catch (IllegalArgumentException ignored) {
            target.playSound(Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.PLAYER, 1.0f, 1.0f));
        }

        if (useCooldown) {
            lastPrivateMessageTime.put(player.getUniqueId(), System.currentTimeMillis());
        }

        return true;
    }

    private Player findOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && exact.isOnline()) {
            return exact;
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getName().equalsIgnoreCase(name)) {
                return onlinePlayer;
            }
        }
        return null;
    }
}
