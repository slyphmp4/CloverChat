package com.slyph.cloverchat.command;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public final class ReloadCommand implements CommandExecutor {

    private final CloverChatPlugin plugin;

    public ReloadCommand(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cloverchat.command.reload")) {
            List<String> noPermission = plugin.messages().getStringList("system-messages.reload-no-permission");
            if (noPermission.isEmpty()) {
                noPermission = Arrays.asList("&7", "&cУ вас нет права на эту команду", "&7");
            }
            plugin.sendConfiguredLines(sender, sender instanceof Player ? (Player) sender : null, noPermission);
            return true;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            List<String> usageLines = plugin.messages().getStringList("system-messages.reload-usage");
            if (usageLines.isEmpty()) {
                usageLines = Arrays.asList("&7", "&eИспользование: /cloverchatreload confirm", "&7");
            }
            plugin.sendConfiguredLines(sender, sender instanceof Player ? (Player) sender : null, usageLines);
            return true;
        }

        Runnable reloadTask = () -> {
            plugin.reloadPluginConfiguration();
            Player player = sender instanceof Player ? (Player) sender : null;
            Runnable responseTask = () -> {
                List<String> successLines = plugin.messages().getStringList("system-messages.reload-success");
                if (successLines.isEmpty()) {
                    successLines = Arrays.asList("&7", "&aCloverChat успешно перезагружен", "&7");
                }
                plugin.sendConfiguredLines(sender, player, successLines);
            };
            if (player == null) {
                responseTask.run();
            } else {
                plugin.scheduler().runEntity(player, responseTask);
            }
        };
        if (plugin.scheduler().isFolia()) {
            plugin.scheduler().runGlobal(reloadTask);
        } else {
            reloadTask.run();
        }
        return true;
    }
}
