package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JoinQuitListener implements Listener {

    private static final String PLAYER_TOKEN = "__cloverchat_join_player_4e27__";
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
        List<String> lines = plugin.messages().getStringList("join-message.lines");
        if (lines.isEmpty()) {
            lines = Arrays.asList("&7", "&a+ %player_name% зашел на сервер", "&7");
        }
        broadcast(joinedPlayer, resolveLines(joinedPlayer, lines));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        event.quitMessage(null);
        Player leftPlayer = event.getPlayer();
        plugin.headMessageService().clear(leftPlayer.getUniqueId());
        if (!plugin.configuration().getBoolean("leave-message.enabled", true)) {
            return;
        }

        List<String> lines = plugin.messages().getStringList("leave-message.lines");
        if (lines.isEmpty()) {
            lines = Arrays.asList("&7", "&c- %player_name% вышел с сервера", "&7");
        }
        broadcast(leftPlayer, resolveLines(leftPlayer, lines));
    }

    private List<Component> resolveLines(Player context, List<String> lines) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            String tokenized = line.replace("%player_name%", PLAYER_TOKEN);
            String resolved = plugin.applyPlaceholders(context, tokenized)
                    .replace(PLAYER_TOKEN, context.getName());
            result.add(plugin.deserializeColored(resolved));
        }
        return List.copyOf(result);
    }

    private void broadcast(Player context, List<Component> lines) {
        for (Player receiver : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().runEntity(receiver, () -> {
                if (!receiver.isOnline()) {
                    return;
                }
                try {
                    if (!receiver.getUniqueId().equals(context.getUniqueId()) && !receiver.canSee(context)) {
                        return;
                    }
                } catch (Exception ignored) {
                    return;
                }
                for (Component line : lines) {
                    receiver.sendMessage(line);
                }
            });
        }
    }
}
