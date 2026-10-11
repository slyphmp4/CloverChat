package com.slyph.cloverchat.feature.redis;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RedisSettingsTest {

    @Test
    void createsStandaloneAndClusterEndpoints() {
        MemoryConfiguration config = new MemoryConfiguration();
        RedisSettings standalone = RedisSettings.read(config, key -> null);
        assertEquals(RedisSettings.Mode.STANDALONE, standalone.mode);
        assertEquals("127.0.0.1", standalone.uris.get(0).getHost());
        config.set("mode", "CLUSTER");
        config.set("cluster.nodes", List.of(Map.of("host", "127.0.0.1", "port", 7000), Map.of("host", "127.0.0.1", "port", 7001)));
        assertEquals(2, RedisSettings.read(config, key -> null).uris.size());
        config.set("cluster.nodes", List.of());
        assertThrows(IllegalArgumentException.class, () -> RedisSettings.read(config, key -> null));
    }

    @Test
    void sentinelHasIndependentAuthenticationAndTls() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("mode", "SENTINEL");
        config.set("sentinel.nodes", List.of(Map.of("host", "127.0.0.1", "port", 26379)));
        config.set("username", "backend");
        config.set("password", "backend-password");
        config.set("sentinel.username", "sentinel-user");
        config.set("ssl.enabled", true);
        config.set("sentinel.ssl.enabled", false);
        RedisSettings settings = RedisSettings.read(config, key -> key.equals("CLOVERCHAT_REDIS_SENTINEL_PASSWORD") ? "sentinel-password" : null);
        assertTrue(settings.uris.get(0).isSsl());
        assertFalse(settings.uris.get(0).getSentinels().get(0).isSsl());
        assertEquals("backend", settings.uris.get(0).getCredentialsProvider().resolveCredentials().block().getUsername());
        assertEquals("sentinel-user", settings.uris.get(0).getSentinels().get(0).getCredentialsProvider().resolveCredentials().block().getUsername());
    }

    @Test
    void refusesInsecureStartTlsAuthenticationAndInvalidEndpoints() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("ssl.start-tls", true);
        assertThrows(IllegalArgumentException.class, () -> RedisSettings.read(config, key -> null));
        config.set("ssl.enabled", true);
        assertTrue(RedisSettings.read(config, key -> null).uris.get(0).isStartTls());
        config.set("password", "secret");
        assertThrows(IllegalArgumentException.class, () -> RedisSettings.read(config, key -> null));
        config.set("password", "");
        config.set("standalone.host", "host/path?password=secret");
        assertThrows(IllegalArgumentException.class, () -> RedisSettings.read(config, key -> null));
    }
}
