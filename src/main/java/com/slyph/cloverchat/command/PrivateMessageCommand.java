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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateMessageCommand implements CommandExecutor {

    private static final String PLAYER_TOKEN = "__cloverchat_pm_player_7a62__";
    private static final String TARGET_TOKEN = "__cloverchat_pm_target_3d91__";
    private static final String MESSAGE_TOKEN = "__cloverchat_pm_message_5f48__";

    private final CloverChatPlugin plugin;
    private final Map<UUID, Long> lastPrivateMessageTime = new ConcurrentHashMap<>();

    public PrivateMessageCommand(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            List<String> nonPlayerLines = plugin.messages().getStringList("private-chat.non-player-message");
            if (nonPlayerLines.isEmpty()) {
                nonPlayerLines = Arrays.asList("&7", "&cЭта команда доступна только игрокам", "&7");
            }
            plugin.sendConfiguredLines(sender, null, nonPlayerLines);
            return true;
        }
        Player player = (Player) sender;

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
            long cooldownSeconds = Math.max(0L, Math.min(
                    plugin.configuration().getLong("private-chat.cooldown-seconds", 5),
                    86400L
            ));
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
                        String resolved = plugin.applyPlaceholders(player, line).replace("%remain%", String.valueOf(remain));
                        player.sendMessage(plugin.deserializeColored(resolved));
                    }
                    return true;
                }
            }
        }

        String targetName = args[0];
        Player target = findOnlinePlayer(player, targetName);
        if (target == null) {
            List<String> offlineLines = plugin.messages().getStringList("private-chat.offline-player-message");
            if (offlineLines.isEmpty()) {
                offlineLines = Arrays.asList("&7", "&cИгрок %target_name% не в сети", "&7");
            }
            for (String line : offlineLines) {
                String resolved = plugin.applyPlaceholders(player, line).replace("%target_name%", targetName);
                player.sendMessage(plugin.deserializeColored(resolved));
            }
            return true;
        }

        String message = plugin.censorService().censor(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        String senderFormat = plugin.messages().getString(
                "private-chat.format-sender",
                "&7[&aВы &8→ &f%target_name%&7] &f%message%"
        );
        String receiverFormat = plugin.messages().getString(
                "private-chat.format-receiver",
                "&7[&f%player_name% &8→ &aВам&7] &f%message%"
        );

        String playerName = player.getName();
        String resolvedTargetName = target.getName();
        String renderedSender = renderFormat(player, senderFormat, playerName, resolvedTargetName, message);
        player.sendMessage(plugin.deserializeColored(renderedSender));

        String soundKey = plugin.configuration().getString(
                "private-chat.notification-sound",
                "minecraft:block.note_block.pling"
        );
        String resolvedSoundKey = soundKey == null || soundKey.isBlank()
                ? "minecraft:block.note_block.pling"
                : soundKey;
        plugin.scheduler().runEntity(target, () -> {
            if (!target.isOnline()) {
                return;
            }
            String renderedReceiver = renderFormat(target, receiverFormat, playerName, resolvedTargetName, message);
            target.sendMessage(plugin.deserializeColored(renderedReceiver));
            try {
                target.playSound(Sound.sound(Key.key(resolvedSoundKey), Sound.Source.PLAYER, 1.0f, 1.0f));
            } catch (IllegalArgumentException ignored) {
                target.playSound(Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.PLAYER, 1.0f, 1.0f));
            }
        });

        if (useCooldown) {
            lastPrivateMessageTime.put(player.getUniqueId(), System.currentTimeMillis());
            trimCooldowns(System.currentTimeMillis());
        }
        return true;
    }

    private String renderFormat(
            Player context,
            String format,
            String playerName,
            String targetName,
            String message
    ) {
        String tokenized = (format == null ? "" : format)
                .replace("%player_name%", PLAYER_TOKEN)
                .replace("%target_name%", TARGET_TOKEN)
                .replace("%message%", MESSAGE_TOKEN);
        return plugin.applyPlaceholders(context, tokenized)
                .replace(PLAYER_TOKEN, playerName)
                .replace(TARGET_TOKEN, targetName)
                .replace(MESSAGE_TOKEN, message);
    }

    private Player findOnlinePlayer(Player viewer, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && exact.isOnline() && viewer.canSee(exact)) {
            return exact;
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getName().equalsIgnoreCase(name) && viewer.canSee(onlinePlayer)) {
                return onlinePlayer;
            }
        }
        return null;
    }

    private void trimCooldowns(long now) {
        if (lastPrivateMessageTime.size() < 1000) {
            return;
        }
        long cutoff = now - 3600000L;
        lastPrivateMessageTime.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
}
