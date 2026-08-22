package com.slyph.cloverchat.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

/**
 * Cardboard compatibility fallback.
 *
 * Cardboard's player-chat pipeline first fires AsyncPlayerChatEvent and then,
 * when at least one legacy PlayerChatEvent listener is registered, forwards the
 * message through PlayerChatEvent before final delivery. Registering this bridge
 * gives CloverChat a second interception point on Cardboard builds where the
 * AsyncPlayerChatEvent listener is not reached correctly.
 */
@SuppressWarnings("deprecation")
public final class CardboardChatBridge implements Listener {

    private final ChatListener chatListener;

    public CardboardChatBridge(ChatListener chatListener) {
        this.chatListener = chatListener;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        event.setCancelled(true);
        chatListener.handleIncomingChat(event.getPlayer().getUniqueId(), event.getMessage());
    }
}
