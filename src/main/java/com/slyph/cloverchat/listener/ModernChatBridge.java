package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;
import java.util.logging.Level;

public final class ModernChatBridge {

    private final CloverChatPlugin plugin;
    private final ChatListener chatListener;
    private Method getPlayerMethod;
    private Method messageMethod;
    private Method setCancelledMethod;
    private boolean executionErrorLogged;
    private boolean serializerInitAttempted;
    private Object plainTextSerializer;
    private Method plainTextSerializeMethod;
    private Method plainTextFactoryMethod;

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

            setCancelledMethod.invoke(event, true);
            Object rawMessage = messageMethod.invoke(event);
            String message = resolveMessageText(rawMessage);
            chatListener.handleIncomingChat(player.getUniqueId(), message);
        } catch (Exception exception) {
            if (!executionErrorLogged) {
                executionErrorLogged = true;
                plugin.getLogger().log(Level.WARNING, "[Chat] Ошибка обработки AsyncChatEvent", exception);
            }
        }
    }

    private String resolveMessageText(Object rawMessage) {
        if (rawMessage instanceof Component) {
            return serializePlain((Component) rawMessage);
        }
        if (rawMessage == null) {
            return "";
        }
        return rawMessage.toString();
    }

    private String serializePlain(Component component) {
        if (!serializerInitAttempted) {
            serializerInitAttempted = true;
            try {
                Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                plainTextFactoryMethod = serializerClass.getMethod("plainText");
                plainTextSerializeMethod = serializerClass.getMethod("serialize", Component.class);
                plainTextSerializer = plainTextFactoryMethod.invoke(null);
            } catch (Exception ignored) {
                plainTextSerializer = null;
                plainTextSerializeMethod = null;
            }
        }

        if (plainTextSerializer != null && plainTextSerializeMethod != null) {
            try {
                Object serialized = plainTextSerializeMethod.invoke(plainTextSerializer, component);
                return serialized == null ? "" : serialized.toString();
            } catch (Exception ignored) {
                return component.toString();
            }
        }

        return component.toString();
    }
}
