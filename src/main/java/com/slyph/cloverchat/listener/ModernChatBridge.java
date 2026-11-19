package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModernChatBridge {

    private final CloverChatPlugin plugin;
    private final ChatListener chatListener;
    private Method getPlayerMethod;
    private Method messageMethod;
    private Method setCancelledMethod;
    private final AtomicBoolean executionErrorLogged = new AtomicBoolean();

    public ModernChatBridge(CloverChatPlugin plugin, ChatListener chatListener) {
        this.plugin = plugin;
        this.chatListener = chatListener;
    }

    @SuppressWarnings("unchecked")
    public boolean register() {
        Class<?> asyncChatEventClass;
        try {
            asyncChatEventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        if (!Event.class.isAssignableFrom(asyncChatEventClass)) {
            return false;
        }

        try {
            getPlayerMethod = asyncChatEventClass.getMethod("getPlayer");
            messageMethod = asyncChatEventClass.getMethod("message");
            setCancelledMethod = asyncChatEventClass.getMethod("setCancelled", boolean.class);
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[Chat] Не удалось подготовить AsyncChatEvent мост", exception);
            return false;
        }

        Listener bridgeListener = new Listener() {
        };
        EventExecutor executor = this::execute;

        plugin.getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) asyncChatEventClass,
                bridgeListener,
                EventPriority.HIGHEST,
                executor,
                plugin,
                true
        );
        return true;
    }

    private void execute(Listener listener, Event event) {
        try {
            Player player = (Player) getPlayerMethod.invoke(event);
            if (player == null || !player.isOnline()) {
                return;
            }

            Object rawMessage = messageMethod.invoke(event);
            String message = resolveMessageText(rawMessage);
            setCancelledMethod.invoke(event, true);
            chatListener.handleIncomingChat(player, message);
        } catch (Exception exception) {
            if (executionErrorLogged.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING, "[Chat] Ошибка обработки AsyncChatEvent", exception);
            }
        }
    }

    static String resolveMessageText(Object rawMessage) {
        if (rawMessage instanceof Component) {
            return PlainTextComponentSerializer.plainText().serialize((Component) rawMessage);
        }
        return rawMessage == null ? "" : rawMessage.toString();
    }
}
