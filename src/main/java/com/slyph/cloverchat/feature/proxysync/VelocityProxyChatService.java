package com.slyph.cloverchat.feature.proxysync;

import com.slyph.cloverchat.CloverChatPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityProxyChatService implements PluginMessageListener {

    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String BUNGEE_CHANNEL_NAMESPACED = "bungeecord:main";
    private static final String FORWARD_SUBCHANNEL = "Forward";
    private static final String PAYLOAD_VERSION = "1";

    private final CloverChatPlugin plugin;
    private final GsonComponentSerializer serializer;
    private final Map<String, Long> seenMessageIds;
    private boolean active;
    private long lastCleanupEpochMillis;

    public VelocityProxyChatService(CloverChatPlugin plugin) {
        this.plugin = plugin;
        this.serializer = GsonComponentSerializer.gson();
        this.seenMessageIds = new ConcurrentHashMap<>();
    }

    public void start() {
        stop();
        if (!plugin.configuration().getBoolean("proxy-sync.enabled", false)) {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
        active = true;
    }

    public void stop() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
        active = false;
        seenMessageIds.clear();
    }

    public void restart() {
        start();
    }

    public void forwardMessage(Player sender, String messageId, String modeName, String viewPermission, Component message) {
        if (!active || message == null || messageId == null || messageId.isBlank()) {
            return;
        }

        SyncMode mode = parseMode(modeName);
        if (mode == null || !isModeEnabled(mode)) {
            return;
        }

        rememberMessageId(messageId);

        String sourceServer = getServerId();
        String payloadJson = serializer.serialize(message);
        ProxyPayload payload = new ProxyPayload(
                PAYLOAD_VERSION,
                sourceServer,
                messageId,
                mode.name(),
                viewPermission == null ? "" : viewPermission,
                payloadJson
        );

        byte[] innerData = encodePayload(payload);
        if (innerData == null || innerData.length == 0) {
            return;
        }

        byte[] transportData = encodeForward(innerData);
        if (transportData == null || transportData.length == 0) {
            return;
        }

        Player carrier = resolveCarrier(sender);
        if (carrier == null) {
            return;
        }

        carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, transportData);
        logDebug("Forwarded proxy chat message id=" + messageId + " mode=" + mode.name() + " source=" + sourceServer);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!active || message == null || message.length == 0) {
            return;
        }
        if (!BUNGEE_CHANNEL.equalsIgnoreCase(channel) && !BUNGEE_CHANNEL_NAMESPACED.equalsIgnoreCase(channel)) {
            return;
        }

        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(message));
            String subchannel = input.readUTF();
            if (!getSubchannel().equalsIgnoreCase(subchannel)) {
                return;
            }

            short length = input.readShort();
            if (length <= 0) {
                return;
            }

            byte[] payloadBytes = new byte[length];
            input.readFully(payloadBytes);
            ProxyPayload payload = decodePayload(payloadBytes);
            if (payload == null) {
                return;
            }

            SyncMode mode = parseMode(payload.mode);
            if (mode == null || !isModeEnabled(mode)) {
                return;
            }

            String localServerId = getServerId();
            if (!payload.sourceServer.isBlank() && payload.sourceServer.equalsIgnoreCase(localServerId)) {
                return;
            }

            if (!rememberMessageId(payload.messageId)) {
                return;
            }

            Component component = serializer.deserialize(payload.componentJson);
            component = applyServerTag(payload.sourceServer, component);
            dispatchIncoming(mode, payload.viewPermission, component);
            logDebug("Received proxy chat message id=" + payload.messageId + " mode=" + payload.mode + " source=" + payload.sourceServer);
        } catch (Exception exception) {
            logDebug("Failed to process proxy-sync message: " + exception.getMessage());
        }
    }

    private void dispatchIncoming(SyncMode mode, String viewPermission, Component message) {
        if (mode == SyncMode.GROUP) {
            String permission = viewPermission == null ? "" : viewPermission;
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (!permission.isBlank() && !onlinePlayer.hasPermission(permission)) {
                    continue;
                }
                onlinePlayer.sendMessage(message);
            }
            return;
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.sendMessage(message);
        }
    }

    private Component applyServerTag(String sourceServer, Component message) {
        if (!plugin.configuration().getBoolean("proxy-sync.server-tag.enabled", true)) {
            return message;
        }
        String format = plugin.configuration().getString("proxy-sync.server-tag.format", "&#65798F[&#AFCFFF%server%&#65798F] ");
        String server = sourceServer == null || sourceServer.isBlank() ? "unknown" : sourceServer;
        String resolved = (format == null ? "" : format).replace("%server%", server);
        return plugin.deserializeColored(resolved).append(message);
    }

    private byte[] encodeForward(byte[] payload) {
        if (payload.length > 32767) {
            return null;
        }
        String targetServer = plugin.configuration().getString("proxy-sync.target-server", "ALL");
        String target = targetServer == null || targetServer.isBlank() ? "ALL" : targetServer;
        String subchannel = getSubchannel();

        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeUTF(FORWARD_SUBCHANNEL);
            output.writeUTF(target);
            output.writeUTF(subchannel);
            output.writeShort(payload.length);
            output.write(payload);
            return stream.toByteArray();
        } catch (Exception exception) {
            return null;
        }
    }

    private byte[] encodePayload(ProxyPayload payload) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeUTF(payload.version);
            output.writeUTF(payload.sourceServer);
            output.writeUTF(payload.messageId);
            output.writeUTF(payload.mode);
            output.writeUTF(payload.viewPermission);
            output.writeUTF(payload.componentJson);
            return stream.toByteArray();
        } catch (Exception exception) {
            return null;
        }
    }

    private ProxyPayload decodePayload(byte[] data) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
            String version = input.readUTF();
            String sourceServer = input.readUTF();
            String messageId = input.readUTF();
            String mode = input.readUTF();
            String viewPermission = input.readUTF();
            String componentJson = input.readUTF();
            if (!PAYLOAD_VERSION.equals(version)) {
                return null;
            }
            if (messageId == null || messageId.isBlank()) {
                return null;
            }
            if (componentJson == null || componentJson.isBlank()) {
                return null;
            }
            return new ProxyPayload(version, sourceServer, messageId, mode, viewPermission, componentJson);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean rememberMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupSeenCache(now);
        Long existing = seenMessageIds.putIfAbsent(messageId, now);
        return existing == null;
    }

    private void cleanupSeenCache(long now) {
        if (now - lastCleanupEpochMillis < 10000L) {
            return;
        }
        lastCleanupEpochMillis = now;
        int ttlSeconds = plugin.configuration().getInt("proxy-sync.dedup-cache-seconds", 120);
        long ttlMillis = Math.max(30L, ttlSeconds) * 1000L;
        seenMessageIds.entrySet().removeIf(entry -> now - entry.getValue() > ttlMillis);
    }

    private Player resolveCarrier(Player sender) {
        if (sender != null && sender.isOnline()) {
            return sender;
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            return onlinePlayer;
        }
        return null;
    }

    private String getServerId() {
        String value = plugin.configuration().getString("proxy-sync.server-id", "server");
        if (value == null || value.isBlank()) {
            return "server";
        }
        return value;
    }

    private String getSubchannel() {
        String value = plugin.configuration().getString("proxy-sync.subchannel", "CloverChatSync");
        if (value == null || value.isBlank()) {
            return "CloverChatSync";
        }
        return value;
    }

    private SyncMode parseMode(String modeName) {
        if (modeName == null || modeName.isBlank()) {
            return null;
        }
        try {
            return SyncMode.valueOf(modeName.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isModeEnabled(SyncMode mode) {
        if (mode == SyncMode.GLOBAL) {
            return plugin.configuration().getBoolean("proxy-sync.sync-modes.global", true);
        }
        if (mode == SyncMode.GROUP) {
            return plugin.configuration().getBoolean("proxy-sync.sync-modes.group", true);
        }
        return plugin.configuration().getBoolean("proxy-sync.sync-modes.local", false);
    }

    private void logDebug(String message) {
        if (plugin.configuration().getBoolean("proxy-sync.debug-log", false)) {
            plugin.getLogger().info("[ProxySync] " + message);
        }
    }

    private enum SyncMode {
        GLOBAL,
        GROUP,
        LOCAL
    }

    private static final class ProxyPayload {
        private final String version;
        private final String sourceServer;
        private final String messageId;
        private final String mode;
        private final String viewPermission;
        private final String componentJson;

        private ProxyPayload(
                String version,
                String sourceServer,
                String messageId,
                String mode,
                String viewPermission,
                String componentJson
        ) {
            this.version = version;
            this.sourceServer = sourceServer == null ? "" : sourceServer;
            this.messageId = messageId == null ? "" : messageId;
            this.mode = mode == null ? "" : mode;
            this.viewPermission = viewPermission == null ? "" : viewPermission;
            this.componentJson = componentJson == null ? "" : componentJson;
        }
    }
}
