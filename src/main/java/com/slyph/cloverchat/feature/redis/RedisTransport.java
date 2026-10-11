package com.slyph.cloverchat.feature.redis;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.DefaultClientResources;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RedisTransport implements AutoCloseable {

    private final AbstractRedisClient client;
    private final DefaultClientResources resources;
    private final List<StatefulConnection<String, String>> connections = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingPublications = new AtomicInteger();
    private final int queueLimit;
    private Function<String, CompletionStage<Long>> publisher;

    private RedisTransport(AbstractRedisClient client, DefaultClientResources resources, int queueLimit) {
        this.client = client;
        this.resources = resources;
        this.queueLimit = queueLimit;
    }

    public static RedisTransport connect(RedisSettings settings, Consumer<String> receiver) {
        DefaultClientResources resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        AbstractRedisClient client = settings.mode == RedisSettings.Mode.CLUSTER
                ? RedisClusterClient.create(resources, settings.uris)
                : RedisClient.create(resources, settings.uris.get(0));
        RedisTransport transport = new RedisTransport(client, resources, settings.queueLimit);
        SocketOptions sockets = SocketOptions.builder().connectTimeout(settings.timeout).keepAlive(true).build();
        RedisPubSubAdapter<String, String> listener = new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                if (!transport.closed.get() && settings.channel.equals(channel)) {
                    receiver.accept(message);
                }
            }
        };
        try {
            if (client instanceof RedisClusterClient) {
                RedisClusterClient cluster = (RedisClusterClient) client;
                cluster.setOptions(ClusterClientOptions.builder()
                        .autoReconnect(true).requestQueueSize(settings.queueLimit)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(sockets).timeoutOptions(TimeoutOptions.enabled(settings.timeout))
                        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                                .enableAllAdaptiveRefreshTriggers().enablePeriodicRefresh(Duration.ofSeconds(30)).build()).build());
                StatefulRedisClusterConnection<String, String> publishing = cluster.connect();
                transport.connections.add(publishing);
                StatefulRedisClusterPubSubConnection<String, String> subscription = cluster.connectPubSub();
                transport.connections.add(subscription);
                subscription.addListener(listener);
                subscription.sync().subscribe(settings.channel);
                transport.publisher = message -> {
                    RedisClusterNode node = publishing.getPartitions().getPartitionBySlot(SlotHash.getSlot(settings.channel));
                    if (node == null) {
                        throw new IllegalStateException("Redis Cluster has no owner for the channel slot");
                    }
                    return publishing.getConnectionAsync(node.getNodeId())
                            .thenCompose(connection -> connection.async().publish(settings.channel, message));
                };
            } else {
                RedisClient standalone = (RedisClient) client;
                standalone.setOptions(ClientOptions.builder()
                        .autoReconnect(true).requestQueueSize(settings.queueLimit)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(sockets).timeoutOptions(TimeoutOptions.enabled(settings.timeout)).build());
                StatefulRedisConnection<String, String> publishing = standalone.connect();
                transport.connections.add(publishing);
                StatefulRedisPubSubConnection<String, String> subscription = standalone.connectPubSub();
                transport.connections.add(subscription);
                subscription.addListener(listener);
                subscription.sync().subscribe(settings.channel);
                transport.publisher = message -> publishing.async().publish(settings.channel, message);
            }
            return transport;
        } catch (RuntimeException exception) {
            transport.close();
            throw exception;
        }
    }

    public CompletionStage<Long> publish(String message) {
        if (closed.get()) {
            throw new IllegalStateException("Redis transport is closed");
        }
        if (pendingPublications.incrementAndGet() > queueLimit) {
            pendingPublications.decrementAndGet();
            throw new IllegalStateException("Redis publication queue is full");
        }
        try {
            return publisher.apply(message).whenComplete((ignored, error) -> pendingPublications.decrementAndGet());
        } catch (RuntimeException exception) {
            pendingPublications.decrementAndGet();
            throw exception;
        }
    }

    public boolean isConnected() {
        return !closed.get() && connections.stream().allMatch(StatefulConnection::isOpen);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        client.shutdownAsync().whenComplete((ignored, error) -> resources.shutdown());
    }
}
