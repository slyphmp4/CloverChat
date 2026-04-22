package com.slyph.cloverchat.command.completer;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CloverChatTabCompleter implements TabCompleter {

    private final CloverChatPlugin plugin;

    public CloverChatTabCompleter(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cloverchat.command.inspect")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            if ("inspect".startsWith(input)) {
                return List.of("inspect");
            }
            return Collections.emptyList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            String prefix = args[1];
            List<String> ids = plugin.messageAuditService().completeMessageIds(prefix, 25);
            if (ids.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(ids);
        }

        return Collections.emptyList();
    }
}
