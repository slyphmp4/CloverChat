package com.slyph.cloverchat.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;

@SuppressWarnings("deprecation")
public final class CardboardChatBridge implements Listener {

    private final ChatListener chatListener;

    public CardboardChatBridge(ChatListener chatListener) {
        this.chatListener = chatListener;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        event.setCancelled(true);
        chatListener.handleIncomingChat(event.getPlayer(), event.getMessage());
    }
}
