package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class QuickPrivateMessageListener implements Listener {

    private final CloverChatPlugin plugin;

    public QuickPrivateMessageListener(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.configuration().getBoolean("quick-pm.enabled", true)) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!(event.getRightClicked() instanceof Player)) {
            return;
        }
        Player target = (Player) event.getRightClicked();

        Player player = event.getPlayer();
        if (player.getUniqueId().equals(target.getUniqueId())) {
            return;
        }

        if (plugin.configuration().getBoolean("quick-pm.require-sneak", false) && !player.isSneaking()) {
            return;
        }

        if (!player.hasPermission("cloverchat.pm") || !player.hasPermission("cloverchat.pm.quick")) {
            return;
        }

        String suggestCommand = plugin.configuration().getString("quick-pm.suggest-command", "/m %target_name% ");
        suggestCommand = replacePlaceholders(player, target, suggestCommand);
        if (suggestCommand.isBlank()) {
            suggestCommand = "/m " + target.getName() + " ";
        }

        List<String> messageLines = plugin.messages().getStringList("quick-pm.message-lines");
        if (messageLines.isEmpty()) {
            messageLines = Arrays.asList(
                    "&7",
                    "&#24e765★ ЛС  &#89ffa6|  &#24e765Нажмите, чтобы написать &#b2ffd8%target_name%",
                    "&7"
            );
        }

        List<String> hoverLines = plugin.hovers().getStringList("quick-pm.hover-lines");
        Component hoverComponent = null;
        if (!hoverLines.isEmpty()) {
            List<String> resolvedHoverLines = new ArrayList<>();
            for (String line : hoverLines) {
                resolvedHoverLines.add(replacePlaceholders(player, target, line));
            }
            hoverComponent = plugin.deserializeColored(String.join("\n", resolvedHoverLines));
        }

        for (String line : messageLines) {
            String resolvedLine = replacePlaceholders(player, target, line);
            Component component = plugin.deserializeColored(resolvedLine)
                    .clickEvent(ClickEvent.suggestCommand(suggestCommand));
            if (hoverComponent != null) {
                component = component.hoverEvent(HoverEvent.showText(hoverComponent));
            }
            player.sendMessage(component);
        }
    }

    private String replacePlaceholders(Player player, Player target, String text) {
        String replaced = text
                .replace("%player_name%", player.getName())
                .replace("%target_name%", target.getName());
        return plugin.applyPlaceholders(player, replaced);
    }
}
