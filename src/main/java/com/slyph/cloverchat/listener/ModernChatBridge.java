package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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

    private static final String[] PLAIN_SERIALIZER_TYPES = {
            "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer",
            "net.kyori.adventure.text.serializer.plain.PlainComponentSerializer"
    };
    private static final PlainSerializerAdapter PLAIN_SERIALIZER = findPlainSerializer();

    static String resolveMessageText(Object rawMessage) {
        if (rawMessage == null) {
            return "";
        }
        if (!(rawMessage instanceof Component)) {
            return rawMessage.toString();
        }
        Component component = (Component) rawMessage;
        if (PLAIN_SERIALIZER != null) {
            try {
                Object result = PLAIN_SERIALIZER.serialize.invoke(PLAIN_SERIALIZER.instance, component);
                if (result instanceof String) {
                    return (String) result;
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Avoid leaking the Adventure debug representation into player chat.
            }
        }

        // Both Adventure 4.x and 5.x expose text components and their children.
        // Incoming player chat is literal text; this is a safe fallback if the
        // serializer is missing or incompatible with the runtime's API.
        StringBuilder plain = new StringBuilder();
        appendText(component, plain);
        return plain.toString();
    }

    private static void appendText(Component component, StringBuilder target) {
        if (component instanceof TextComponent) {
            target.append(((TextComponent) component).content());
        }
        for (Component child : component.children()) {
            appendText(child, target);
        }
    }

    private static PlainSerializerAdapter findPlainSerializer() {
        for (String serializerType : PLAIN_SERIALIZER_TYPES) {
            try {
                Class<?> type = Class.forName(serializerType);
                Method factory = type.getMethod("plainText");
                Object instance = factory.invoke(null);
                Method serialize = type.getMethod("serialize", Component.class);
                return new PlainSerializerAdapter(instance, serialize);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Keep the bridge compatible with the older Adventure shipped
                // in the legacy Paper API as well as the API in Minecraft 26.3.
            }
        }
        return null;
    }

    private static final class PlainSerializerAdapter {
        private final Object instance;
        private final Method serialize;

        private PlainSerializerAdapter(Object instance, Method serialize) {
            this.instance = instance;
            this.serialize = serialize;
        }
    }
}
