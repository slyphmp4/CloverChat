package com.slyph.cloverchat.feature.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CLOVERCHAT_REDIS_TESTS", matches = "true")
final class RedisTransportIntegrationTest {

    @BeforeAll
    static void configureTrust() {
        String truststore = System.getenv("CLOVERCHAT_REDIS_TEST_TRUSTSTORE");
        if (truststore != null) {
            System.setProperty("javax.net.ssl.trustStore", truststore);
            System.setProperty("javax.net.ssl.trustStorePassword", "fixture-only");
        }
    }

    @Test
    void standaloneWithAclPublishesExactlyOnce() throws Exception {
        roundTrip(standalone(false));
    }

    @Test
    void sentinelUsesSeparateAclCredentials() throws Exception {
        MemoryConfiguration config = standalone(false);
        config.set("mode", "SENTINEL");
        config.set("sentinel.nodes", List.of(Map.of("host", "127.0.0.1", "port", 26389)));
        config.set("sentinel.master-name", "clover-master");
        config.set("sentinel.username", "sentinel-user");
        config.set("sentinel.password", "sentinel-only-password");
        roundTrip(config);
    }

    @Test
    void clusterPublishesAcrossNodes() throws Exception {
        MemoryConfiguration config = standalone(false);
        config.set("mode", "CLUSTER");
        config.set("cluster.nodes", List.of(Map.of("host", "127.0.0.1", "port", 17000),
                Map.of("host", "127.0.0.1", "port", 17001), Map.of("host", "127.0.0.1", "port", 17002)));
        RedisSettings settings = RedisSettings.read(config, key -> null);
        LinkedBlockingQueue<String> firstNode = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> lastNode = new LinkedBlockingQueue<>();
        RedisClient first = RedisClient.create(settings.uris.get(0));
        RedisClient last = RedisClient.create(settings.uris.get(2));
        try (RedisTransport publisher = RedisTransport.connect(settings, ignored -> {})) {
            subscribe(first, settings.channel, firstNode);
            subscribe(last, settings.channel, lastNode);
            publisher.publish("cluster-packet").toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("cluster-packet", firstNode.poll(10, TimeUnit.SECONDS));
            assertEquals("cluster-packet", lastNode.poll(10, TimeUnit.SECONDS));
            assertNull(firstNode.poll(250, TimeUnit.MILLISECONDS));
            assertNull(lastNode.poll(250, TimeUnit.MILLISECONDS));
        } finally {
            first.shutdown();
            last.shutdown();
        }
    }

    @Test
    void tlsVerifiesCertificateAndSupportsAcl() throws Exception {
        roundTrip(standalone(true));
    }

    @Test
    void rejectsWrongAclPassword() {
        MemoryConfiguration config = standalone(false);
        config.set("password", "wrong");
        assertThrows(RuntimeException.class, () -> RedisTransport.connect(RedisSettings.read(config, key -> null), ignored -> {}));
    }

    private MemoryConfiguration standalone(boolean tls) {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("standalone.host", "127.0.0.1");
        config.set("standalone.port", tls ? 16443 : 16379);
        config.set("ssl.enabled", tls);
        config.set("ssl.verify-peer", true);
        config.set("username", "clover");
        config.set("password", "test-only-password");
        config.set("channel", "cloverchat:test:" + UUID.randomUUID());
        return config;
    }

    private void roundTrip(MemoryConfiguration config) throws Exception {
        RedisSettings settings = RedisSettings.read(config, key -> null);
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (RedisTransport subscriber = RedisTransport.connect(settings, received::add);
             RedisTransport publisher = RedisTransport.connect(settings, ignored -> {})) {
            assertTrue(subscriber.isConnected());
            publisher.publish("signed-packet-fixture").toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals("signed-packet-fixture", received.poll(10, TimeUnit.SECONDS));
            assertNull(received.poll(250, TimeUnit.MILLISECONDS));
        }
    }

    private void subscribe(RedisClient client, String channel, LinkedBlockingQueue<String> received) {
        StatefulRedisPubSubConnection<String, String> subscription = client.connectPubSub();
        subscription.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String actualChannel, String message) {
                if (channel.equals(actualChannel)) {
                    received.add(message);
                }
            }
        });
        subscription.sync().subscribe(channel);
    }
}
