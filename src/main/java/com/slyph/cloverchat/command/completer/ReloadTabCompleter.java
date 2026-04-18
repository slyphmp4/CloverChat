package com.slyph.cloverchat.command.completer;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public final class ReloadTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cloverchat.command.reload")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return "confirm".startsWith(args[0].toLowerCase()) ? List.of("confirm") : Collections.emptyList();
        }

        return Collections.emptyList();
    }
}
