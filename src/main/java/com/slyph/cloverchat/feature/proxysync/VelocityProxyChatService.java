package com.slyph.cloverchat.feature.proxysync;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.feature.messageinspect.MessageIdGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityProxyChatService implements PluginMessageListener {

    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String BUNGEE_CHANNEL_NAMESPACED = "bungeecord:main";
    private static final String FORWARD_SUBCHANNEL = "Forward";

    private final CloverChatPlugin plugin;
    private final GsonComponentSerializer serializer = GsonComponentSerializer.gson();
    private final Map<String, Long> seenMessageIds = new ConcurrentHashMap<>();
    private volatile boolean active;
    private volatile long lastCleanupEpochMillis;
    private volatile ProxyPayloadCodec codec;

    public VelocityProxyChatService(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.configuration().getBoolean("proxy-sync.enabled", false)) {
            return;
        }

        String sharedSecret = resolveSharedSecret();
        if (!ProxyPayloadCodec.isStrongSecret(sharedSecret)) {
            plugin.getLogger().warning("[ProxySync] Синхронизация отключена: задайте общий секрет длиной от 32 байт через CLOVERCHAT_PROXY_SECRET или proxy-sync.shared-secret");
            return;
        }

        try {
            codec = new ProxyPayloadCodec(sharedSecret);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
            active = true;
        } catch (Exception exception) {
            codec = null;
            active = false;
            plugin.getLogger().warning("[ProxySync] Не удалось запустить синхронизацию: " + exception.getMessage());
        }
    }

    public void stop() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
        active = false;
        codec = null;
        seenMessageIds.clear();
    }

    public void restart() {
        start();
    }

    public void forwardGlobalMessage(Player sender, String messageId, Component message) {
        ProxyPayloadCodec activeCodec = codec;
        if (!active || activeCodec == null || message == null || messageId == null || messageId.isBlank()) {
            return;
        }

        rememberMessageId(messageId);
        String sourceServer = getServerId();
        ProxyPayloadCodec.Payload payload = new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                System.currentTimeMillis(),
                sourceServer,
                messageId,
                "GLOBAL",
                "",
                serializer.serialize(message)
        );

        byte[] innerData = activeCodec.encode(payload);
        if (innerData == null || innerData.length == 0) {
            logDebug("Global message payload is too large or invalid: " + messageId);
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

        plugin.scheduler().runEntity(carrier, () -> {
            if (!active || !carrier.isOnline()) {
                return;
            }
            carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, transportData);
            logDebug("Forwarded global chat message id=" + messageId + " source=" + sourceServer);
        });
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        ProxyPayloadCodec activeCodec = codec;
        if (!active || activeCodec == null || message == null || message.length == 0 || message.length > 32767) {
            return;
        }
        if (!BUNGEE_CHANNEL.equalsIgnoreCase(channel) && !BUNGEE_CHANNEL_NAMESPACED.equalsIgnoreCase(channel)) {
            return;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String subchannel = input.readUTF();
            if (!getSubchannel().equalsIgnoreCase(subchannel)) {
                return;
            }

            int length = input.readUnsignedShort();
            if (length <= 0 || length > ProxyPayloadCodec.MAX_PACKET_BYTES || input.available() != length) {
                return;
            }

            byte[] payloadBytes = new byte[length];
            input.readFully(payloadBytes);
            long maxAgeMillis = Math.max(5L, Math.min(
                    plugin.configuration().getLong("proxy-sync.max-message-age-seconds", 45L),
                    300L
            )) * 1000L;
            ProxyPayloadCodec.Payload payload = activeCodec.decode(payloadBytes, System.currentTimeMillis(), maxAgeMillis);
            if (payload == null) {
                logDebug("Rejected unsigned, expired or invalid proxy message");
                return;
            }

            if (payload.sourceServer().equalsIgnoreCase(getServerId())) {
                return;
            }
            if (!rememberMessageId(payload.messageId())) {
                return;
            }

            Component component = serializer.deserialize(payload.componentJson());
            Component tagged = applyServerTag(payload.sourceServer(), component);
            dispatchIncoming(tagged);
            logDebug("Received global chat message id=" + payload.messageId() + " source=" + payload.sourceServer());
        } catch (Exception exception) {
            logDebug("Failed to process proxy-sync message: " + exception.getMessage());
        }
    }

    private void dispatchIncoming(Component message) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            plugin.scheduler().runEntity(onlinePlayer, () -> {
                if (onlinePlayer.isOnline()) {
                    onlinePlayer.sendMessage(message);
                }
            });
        }
    }

    private Component applyServerTag(String sourceServer, Component message) {
        if (!plugin.configuration().getBoolean("proxy-sync.server-tag.enabled", true)) {
            return message;
        }
        String format = plugin.configuration().getString("proxy-sync.server-tag.format", "&#65798F[&#AFCFFF%server%&#65798F] ");
        String resolved = (format == null ? "" : format).replace("%server%", sourceServer);
        return plugin.deserializeColored(resolved).append(message);
    }

    private byte[] encodeForward(byte[] payload) {
        if (payload.length > ProxyPayloadCodec.MAX_PACKET_BYTES) {
            return null;
        }
        String target = getTargetServer();
        String subchannel = getSubchannel();

        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(stream)) {
                output.writeUTF(FORWARD_SUBCHANNEL);
                output.writeUTF(target);
                output.writeUTF(subchannel);
                output.writeShort(payload.length);
                output.write(payload);
            }
            byte[] encoded = stream.toByteArray();
            return encoded.length <= 32767 ? encoded : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean rememberMessageId(String messageId) {
        if (messageId == null || !messageId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}")) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupSeenCache(now);
        int limit = Math.max(1000, Math.min(
                plugin.configuration().getInt("proxy-sync.max-dedup-entries", 10000),
                100000
        ));
        if (seenMessageIds.size() >= limit) {
            removeOldestSeenMessage();
        }
        return seenMessageIds.putIfAbsent(messageId, now) == null;
    }

    private void cleanupSeenCache(long now) {
        if (now - lastCleanupEpochMillis < 10000L) {
            return;
        }
        lastCleanupEpochMillis = now;
        int ttlSeconds = plugin.configuration().getInt("proxy-sync.dedup-cache-seconds", 120);
        long ttlMillis = Math.max(30L, Math.min(ttlSeconds, 3600)) * 1000L;
        seenMessageIds.entrySet().removeIf(entry -> now - entry.getValue() > ttlMillis);
    }

    private void removeOldestSeenMessage() {
        String oldestId = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, Long> entry : seenMessageIds.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) {
            seenMessageIds.remove(oldestId, oldestTime);
        }
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

    private String resolveSharedSecret() {
        String environmentSecret = System.getenv("CLOVERCHAT_PROXY_SECRET");
        if (environmentSecret != null && !environmentSecret.isBlank()) {
            return environmentSecret;
        }
        return plugin.configuration().getString("proxy-sync.shared-secret", "");
    }

    private String getServerId() {
        return MessageIdGenerator.sanitizeServerId(
                plugin.configuration().getString("proxy-sync.server-id", "server")
        );
    }

    private String getTargetServer() {
        String value = plugin.configuration().getString("proxy-sync.target-server", "ALL");
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) {
            return "ALL";
        }
        return value;
    }

    private String getSubchannel() {
        String value = plugin.configuration().getString("proxy-sync.subchannel", "CloverChatSync");
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return "CloverChatSync";
        }
        return value;
    }

    private void logDebug(String message) {
        if (plugin.configuration().getBoolean("proxy-sync.debug-log", false)) {
            plugin.getLogger().info("[ProxySync] " + message);
        }
    }
}
