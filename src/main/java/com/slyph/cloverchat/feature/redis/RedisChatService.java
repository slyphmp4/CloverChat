package com.slyph.cloverchat.feature.redis;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.feature.cache.BoundedTtlCache;
import com.slyph.cloverchat.feature.messageinspect.MessageIdGenerator;
import com.slyph.cloverchat.feature.proxysync.ProxyPayloadCodec;
import com.slyph.cloverchat.util.CompatScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RedisChatService {

    private final CloverChatPlugin plugin;
    private final GsonComponentSerializer serializer = GsonComponentSerializer.gson();
    private volatile Session session;
    private CompatScheduler.TaskHandle retryTask;

    public RedisChatService(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.configuration().getBoolean("proxy-sync.enabled", false)
                || !"REDIS".equalsIgnoreCase(plugin.configuration().getString("proxy-sync.transport", "VELOCITY"))) {
            return;
        }
        try {
            String secret = System.getenv("CLOVERCHAT_PROXY_SECRET");
            if (secret == null || secret.isBlank()) {
                secret = plugin.configuration().getString("proxy-sync.shared-secret", "");
            }
            RedisSettings settings = RedisSettings.read(plugin.configuration().getConfigurationSection("proxy-sync.redis"), System::getenv);
            Session next = new Session(settings, new ProxyPayloadCodec(secret),
                    MessageIdGenerator.sanitizeServerId(plugin.configuration().getString("proxy-sync.server-id", "server")),
                    Math.max(5, Math.min(300, plugin.configuration().getLong("proxy-sync.max-message-age-seconds", 45))) * 1000L,
                    Math.max(30, Math.min(3600, plugin.configuration().getLong("proxy-sync.dedup-cache-seconds", 120))) * 1000L,
                    Math.max(1000, Math.min(100000, plugin.configuration().getInt("proxy-sync.max-dedup-entries", 10000))));
            session = next;
            long retryTicks = Math.max(5, Math.min(300, plugin.configuration().getLong("proxy-sync.redis.retry-seconds", 10))) * 20L;
            retryTask = plugin.scheduler().runAsyncRepeating(() -> connect(next), 1L, retryTicks);
        } catch (Exception exception) {
            plugin.getLogger().warning("[Redis] Configuration rejected: " + exception.getMessage());
        }
    }

    public void stop() {
        Session previous = session;
        session = null;
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
        if (previous != null) {
            synchronized (previous) {
                previous.closed.set(true);
                RedisTransport transport = previous.transport;
                if (transport != null) {
                    transport.close();
                }
            }
        }
    }

    public void restart() {
        start();
    }

    private void connect(Session current) {
        if (session != current || current.closed.get() || !current.connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            RedisTransport previous = current.transport;
            if (previous != null) {
                if (previous.isConnected()) {
                    return;
                }
                current.transport = null;
                previous.close();
            }
            RedisTransport transport = RedisTransport.connect(current.settings, encoded -> receive(current, encoded));
            synchronized (current) {
                if (session != current || current.closed.get()) {
                    transport.close();
                } else {
                    current.transport = transport;
                    plugin.getLogger().info("[Redis] Connected: " + current.settings.mode);
                }
            }
        } catch (Exception exception) {
            logFailure(current);
        } finally {
            current.connecting.set(false);
        }
    }

    public void forwardGlobalMessage(Player sender, String messageId, Component message) {
        Session current = session;
        if (current == null || current.closed.get() || current.transport == null) {
            return;
        }
        byte[] packet = current.codec.encode(new ProxyPayloadCodec.Payload(ProxyPayloadCodec.VERSION,
                System.currentTimeMillis(), current.serverId, messageId, "GLOBAL", "", serializer.serialize(message)));
        if (packet == null) {
            return;
        }
        try {
            current.transport.publish(Base64.getEncoder().encodeToString(packet)).whenComplete((count, error) -> {
                if (error != null) {
                    logFailure(current);
                }
            });
        } catch (Exception exception) {
            logFailure(current);
        }
    }

    private void receive(Session current, String encoded) {
        if (session != current || current.closed.get() || encoded == null || encoded.length() > 40000) {
            return;
        }
        try {
            ProxyPayloadCodec.Payload payload = current.codec.decode(Base64.getDecoder().decode(encoded),
                    System.currentTimeMillis(), current.maxAgeMillis);
            if (payload == null || current.serverId.equalsIgnoreCase(payload.sourceServer())) {
                return;
            }
            if (!current.seen.markIfAbsent(payload.messageId(), current.dedupMillis, Boolean.TRUE)) {
                return;
            }
            Component message = serializer.deserialize(payload.componentJson());
            if (plugin.configuration().getBoolean("proxy-sync.server-tag.enabled", true)) {
                String format = plugin.configuration().getString("proxy-sync.server-tag.format", "&7[%server%] ");
                message = plugin.deserializeFormat(format.replace("%server%", payload.sourceServer())).append(message);
            }
            Component delivered = message;
            plugin.scheduler().runGlobal(() -> {
                if (session != current || current.closed.get()) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    plugin.scheduler().runEntity(player, () -> {
                        if (session == current && !current.closed.get() && player.isOnline()) {
                            player.sendMessage(delivered);
                        }
                    });
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void logFailure(Session current) {
        synchronized (current) {
            long now = System.currentTimeMillis();
            if (session == current && !current.closed.get() && now - current.lastWarning >= 30000) {
                current.lastWarning = now;
                plugin.getLogger().warning("[Redis] Connection/publication failed; local chat remains available. Check endpoints, ACL and TLS settings.");
            }
        }
    }

    private static final class Session {
        private final RedisSettings settings;
        private final ProxyPayloadCodec codec;
        private final String serverId;
        private final long maxAgeMillis;
        private final long dedupMillis;
        private final BoundedTtlCache<String, Boolean> seen;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean connecting = new AtomicBoolean();
        private volatile RedisTransport transport;
        private long lastWarning;

        private Session(RedisSettings settings, ProxyPayloadCodec codec, String serverId, long maxAgeMillis, long dedupMillis, int limit) {
            this.settings = settings;
            this.codec = codec;
            this.serverId = serverId;
            this.maxAgeMillis = maxAgeMillis;
            this.dedupMillis = dedupMillis;
            seen = new BoundedTtlCache<>(limit);
        }
    }
}
