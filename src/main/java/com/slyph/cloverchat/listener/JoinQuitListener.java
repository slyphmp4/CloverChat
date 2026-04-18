package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.List;

public final class JoinQuitListener implements Listener {

    private final CloverChatPlugin plugin;

    public JoinQuitListener(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.configuration().getBoolean("join-message.enabled", true)) {
            return;
        }

        event.joinMessage(null);
        Player joinedPlayer = event.getPlayer();

        List<String> lines = plugin.configuration().getStringList("join-message.lines");
        if (lines.isEmpty()) {
            lines = Arrays.asList("&7", "&a+ %player_name% зашел на сервер", "&7");
        }

        for (Player receiver : Bukkit.getOnlinePlayers()) {
            sendLines(receiver, joinedPlayer, lines);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player leftPlayer = event.getPlayer();
        plugin.headMessageService().clear(leftPlayer.getUniqueId());

        if (!plugin.configuration().getBoolean("leave-message.enabled", true)) {
            return;
        }

        event.quitMessage(null);

        List<String> lines = plugin.configuration().getStringList("leave-message.lines");
        if (lines.isEmpty()) {
            lines = Arrays.asList("&7", "&c- %player_name% вышел с сервера", "&7");
        }

        for (Player receiver : Bukkit.getOnlinePlayers()) {
            sendLines(receiver, leftPlayer, lines);
        }
    }

    private void sendLines(Player receiver, Player context, List<String> lines) {
        for (String line : lines) {
            String resolved = line.replace("%player_name%", context.getName());
            resolved = plugin.applyPlaceholders(context, resolved);
            receiver.sendMessage(plugin.deserializeColored(resolved));
        }
    }
}
