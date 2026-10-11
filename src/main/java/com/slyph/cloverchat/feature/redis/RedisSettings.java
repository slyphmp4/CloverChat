package com.slyph.cloverchat.feature.redis;

import io.lettuce.core.RedisURI;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class RedisSettings {

    public enum Mode { STANDALONE, SENTINEL, CLUSTER }

    public final Mode mode;
    public final String channel;
    public final Duration timeout;
    public final int queueLimit;
    public final List<RedisURI> uris;

    private RedisSettings(Mode mode, String channel, Duration timeout, int queueLimit, List<RedisURI> uris) {
        this.mode = mode;
        this.channel = channel;
        this.timeout = timeout;
        this.queueLimit = queueLimit;
        this.uris = List.copyOf(uris);
    }

    public static RedisSettings read(ConfigurationSection config, Function<String, String> environment) {
        Mode mode = Mode.valueOf(config.getString("mode", "STANDALONE").toUpperCase(java.util.Locale.ROOT));
        String channel = config.getString("channel", "cloverchat:global");
        if (channel == null || !channel.matches("[A-Za-z0-9_.:{}-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Redis channel");
        }
        Duration timeout = Duration.ofMillis(Math.max(500, Math.min(30000, config.getLong("timeout-millis", 5000))));
        String username = credential(config, environment, "CLOVERCHAT_REDIS_USERNAME", "username");
        String password = credential(config, environment, "CLOVERCHAT_REDIS_PASSWORD", "password");
        boolean ssl = config.getBoolean("ssl.enabled", false);
        boolean startTls = config.getBoolean("ssl.start-tls", false);
        boolean verifyPeer = config.getBoolean("ssl.verify-peer", true);
        validateTls(ssl, startTls, username, password);
        List<RedisURI> uris = new ArrayList<>();
        if (mode == Mode.STANDALONE) {
            RedisURI uri = node(config.getString("standalone.host", "127.0.0.1"), config.getInt("standalone.port", 6379), timeout);
            configure(uri, username, password, ssl, startTls, verifyPeer);
            uris.add(uri);
        } else if (mode == Mode.CLUSTER) {
            for (Map<?, ?> entry : config.getMapList("cluster.nodes")) {
                RedisURI uri = node(String.valueOf(entry.get("host")), port(entry.get("port")), timeout);
                configure(uri, username, password, ssl, startTls, verifyPeer);
                uris.add(uri);
            }
        } else {
            String master = config.getString("sentinel.master-name", "mymaster");
            if (master == null || !master.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("Invalid Sentinel master name");
            }
            List<Map<?, ?>> nodes = config.getMapList("sentinel.nodes");
            if (nodes.isEmpty() || nodes.size() > 32) {
                throw new IllegalArgumentException("Sentinel nodes are required");
            }
            RedisURI uri = RedisURI.Builder.sentinel(String.valueOf(nodes.get(0).get("host")), port(nodes.get(0).get("port")), master)
                    .withTimeout(timeout).build();
            configure(uri, username, password, ssl, startTls, verifyPeer);
            for (int i = 1; i < nodes.size(); i++) {
                uri.getSentinels().add(node(String.valueOf(nodes.get(i).get("host")), port(nodes.get(i).get("port")), timeout));
            }
            String sentinelUser = credential(config, environment, "CLOVERCHAT_REDIS_SENTINEL_USERNAME", "sentinel.username");
            String sentinelPassword = credential(config, environment, "CLOVERCHAT_REDIS_SENTINEL_PASSWORD", "sentinel.password");
            boolean sentinelSsl = config.getBoolean("sentinel.ssl.enabled", ssl);
            boolean sentinelStartTls = config.getBoolean("sentinel.ssl.start-tls", false);
            boolean sentinelVerify = config.getBoolean("sentinel.ssl.verify-peer", true);
            validateTls(sentinelSsl, sentinelStartTls, sentinelUser, sentinelPassword);
            for (RedisURI sentinel : uri.getSentinels()) {
                validateHost(sentinel.getHost());
                sentinel.setTimeout(timeout);
                configure(sentinel, sentinelUser, sentinelPassword, sentinelSsl, sentinelStartTls, sentinelVerify);
            }
            uris.add(uri);
        }
        if (uris.isEmpty() || uris.size() > 32) {
            throw new IllegalArgumentException("Redis requires between 1 and 32 seed nodes");
        }
        return new RedisSettings(mode, channel, timeout,
                Math.max(16, Math.min(10000, config.getInt("request-queue-limit", 512))), uris);
    }

    private static void validateTls(boolean ssl, boolean startTls, String username, String password) {
        if (startTls && !ssl) {
            throw new IllegalArgumentException("StartTLS requires ssl.enabled: true");
        }
        if (startTls && (!username.isEmpty() || !password.isEmpty())) {
            throw new IllegalArgumentException("Use direct TLS with ACL/password; StartTLS cannot send credentials safely");
        }
    }

    private static RedisURI node(String host, int port, Duration timeout) {
        validateHost(host);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Redis port");
        }
        return RedisURI.Builder.redis(host, port).withTimeout(timeout).build();
    }

    private static void validateHost(String host) {
        if (host == null || !host.matches("[A-Za-z0-9_.:\\[\\]-]{1,255}") || host.equals("null")) {
            throw new IllegalArgumentException("Invalid Redis host");
        }
    }

    private static int port(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Redis node port must be numeric");
        }
    }

    private static void configure(RedisURI uri, String user, String password, boolean ssl, boolean startTls, boolean verifyPeer) {
        uri.setSsl(ssl);
        uri.setStartTls(startTls);
        uri.setVerifyPeer(verifyPeer);
        if (!user.isEmpty()) {
            uri.setAuthentication(user, password.toCharArray());
        } else if (!password.isEmpty()) {
            uri.setAuthentication(password.toCharArray());
        }
    }

    private static String credential(ConfigurationSection config, Function<String, String> environment, String variable, String path) {
        String value = environment.apply(variable);
        return value == null || value.isEmpty() ? config.getString(path, "") : value;
    }
}
