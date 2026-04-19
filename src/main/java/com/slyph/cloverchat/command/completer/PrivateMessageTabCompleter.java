package com.slyph.cloverchat.command.completer;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PrivateMessageTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cloverchat.pm")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            Player senderPlayer = sender instanceof Player ? (Player) sender : null;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (senderPlayer != null && !senderPlayer.canSee(player)) {
                    continue;
                }
                String name = player.getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(input)) {
                    suggestions.add(name);
                }
            }

            Collections.sort(suggestions);
            return suggestions;
        }

        return Collections.emptyList();
    }
}
