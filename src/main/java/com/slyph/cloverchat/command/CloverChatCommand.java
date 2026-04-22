package com.slyph.cloverchat.command;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.feature.messageinspect.model.ChatMessageAuditRecord;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class CloverChatCommand implements CommandExecutor {

    private final CloverChatPlugin plugin;

    public CloverChatCommand(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.usage", fallbackUsage()));
            return true;
        }

        String subcommand = args[0].toLowerCase();
        if (!subcommand.equals("inspect")) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.usage", fallbackUsage()));
            return true;
        }

        if (!sender.hasPermission("cloverchat.command.inspect")) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.no-permission", fallbackNoPermission()));
            return true;
        }

        if (!plugin.messageAuditService().isEnabled()) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.disabled", fallbackDisabled()));
            return true;
        }

        if (args.length < 2 || args[1].isBlank()) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.usage", fallbackUsage()));
            return true;
        }

        String messageId = args[1].trim();
        sendList(sender, senderAsPlayer(sender), readList("inspect-messages.loading", fallbackLoading()));

        plugin.messageAuditService().findByMessageIdAsync(
                messageId,
                record -> onInspectLoaded(sender, record),
                throwable -> sendList(sender, senderAsPlayer(sender), readList("inspect-messages.error", fallbackError()))
        );
        return true;
    }

    private void onInspectLoaded(CommandSender sender, ChatMessageAuditRecord record) {
        if (record == null) {
            sendList(sender, senderAsPlayer(sender), readList("inspect-messages.not-found", fallbackNotFound()));
            return;
        }

        List<String> lines = readList("inspect-messages.result", fallbackResult());
        Map<String, String> placeholders = plugin.messageAuditService().buildPlaceholders(record);
        sendListWithReplacements(sender, senderAsPlayer(sender), lines, placeholders);
    }

    private Player senderAsPlayer(CommandSender sender) {
        return sender instanceof Player ? (Player) sender : null;
    }

    private List<String> readList(String path, List<String> fallback) {
        List<String> lines = plugin.messages().getStringList(path);
        if (lines == null || lines.isEmpty()) {
            return fallback;
        }
        return lines;
    }

    private void sendList(CommandSender sender, Player context, List<String> lines) {
        for (String line : lines) {
            String resolved = plugin.applyPlaceholders(context, line);
            sender.sendMessage(plugin.deserializeColored(resolved));
        }
    }

    private void sendListWithReplacements(
            CommandSender sender,
            Player context,
            List<String> lines,
            Map<String, String> placeholders
    ) {
        for (String line : lines) {
            String replaced = line;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                replaced = replaced.replace(entry.getKey(), entry.getValue());
            }
            String resolved = plugin.applyPlaceholders(context, replaced);
            sender.sendMessage(plugin.deserializeColored(resolved));
        }
    }

    private List<String> fallbackUsage() {
        return Arrays.asList(
                "&7",
                "&#24e765★ Inspect  &#89ffa6|  &#24e765Использование: /cloverchat inspect <message_id>",
                "&7"
        );
    }

    private List<String> fallbackNoPermission() {
        return Arrays.asList(
                "&7",
                "&#ff6b6b✖ Ошибка  &#ffc1c1|  &#ff8f8fУ вас нет права на эту команду",
                "&7"
        );
    }

    private List<String> fallbackDisabled() {
        return Arrays.asList(
                "&7",
                "&#ff6b6b✖ Ошибка  &#ffc1c1|  &#ff8f8fMessage Inspector выключен в config.yml",
                "&7"
        );
    }

    private List<String> fallbackLoading() {
        return Arrays.asList(
                "&7",
                "&#24e765★ Inspect  &#89ffa6|  &#24e765Поиск сообщения по ID...",
                "&7"
        );
    }

    private List<String> fallbackNotFound() {
        return Arrays.asList(
                "&7",
                "&#ff6b6b✖ Ошибка  &#ffc1c1|  &#ff8f8fСообщение с таким ID не найдено",
                "&7"
        );
    }

    private List<String> fallbackError() {
        return Arrays.asList(
                "&7",
                "&#ff6b6b✖ Ошибка  &#ffc1c1|  &#ff8f8fНе удалось получить данные из базы",
                "&7"
        );
    }

    private List<String> fallbackResult() {
        return Arrays.asList(
                "&7",
                "&#24e765★ Inspect  &#89ffa6|  &#24e765ID: &#d6fff7%message_id%",
                "&#CBFFF7✎ Автор: &#90FFE7%player_name% &#A5A5A5(%player_uuid%)",
                "&#CBFFF7⚑ Группа: &#90FFE7%player_group%",
                "&#CBFFF7⚑ Префикс: &#90FFE7%player_prefix%",
                "&#CBFFF7⚑ Репутация: &#90FFE7%player_reputation%",
                "&#CBFFF7🕓 Время: &#90FFE7%message_time% &#A5A5A5(%message_created_at%)",
                "&#CBFFF7🌐 Сервер: &#90FFE7%server_id% &#A5A5A5| &#90FFE7%world% &#A5A5A5X:%x% Y:%y% Z:%z%",
                "&#CBFFF7📡 Режим: &#90FFE7%chat_mode% &#A5A5A5| &#90FFE7%chat_type% &#A5A5A5| &#90FFE7%group_channel%",
                "&#CBFFF7🔒 View permission: &#90FFE7%view_permission%",
                "&#CBFFF7🧾 Raw: &#90FFE7%message_raw%",
                "&#CBFFF7💬 Final: &#90FFE7%message_final%",
                "&7"
        );
    }
}
