package com.slyph.cloverchat.feature.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class BoundedTtlCache<K, V> {

    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maximumSize;
    private final LongSupplier clock;

    public BoundedTtlCache(int maximumSize) {
        this(maximumSize, System::nanoTime);
    }

    public BoundedTtlCache(int maximumSize, LongSupplier clock) {
        this.maximumSize = Math.max(1, maximumSize);
        this.clock = clock;
    }

    public V get(K key, long ttlMillis, Supplier<V> loader) {
        return get(key, ttlMillis, loader, value -> value != null);
    }

    public V get(K key, long ttlMillis, Supplier<V> loader, Predicate<V> cacheable) {
        if (ttlMillis <= 0) {
            return loader.get();
        }
        synchronized (entries) {
            Entry<V> entry = entries.get(key);
            if (entry != null) {
                if (clock.getAsLong() - entry.createdAt < entry.ttlNanos) {
                    return entry.value;
                }
                entries.remove(key);
            }
        }
        V value = loader.get();
        if (cacheable.test(value)) {
            long ttlNanos = Math.min(ttlMillis, 86400000L) * 1000000L;
            synchronized (entries) {
                entries.put(key, new Entry<>(value, clock.getAsLong(), ttlNanos));
                while (entries.size() > maximumSize) {
                    Iterator<K> iterator = entries.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        return value;
    }

    public void invalidateMatching(Predicate<K> predicate) {
        synchronized (entries) {
            entries.keySet().removeIf(predicate);
        }
    }

    public boolean markIfAbsent(K key, long ttlMillis, V value) {
        synchronized (entries) {
            Entry<V> entry = entries.get(key);
            long now = clock.getAsLong();
            if (entry != null && now - entry.createdAt < entry.ttlNanos) {
                return false;
            }
            entries.put(key, new Entry<>(value, now, Math.max(1, Math.min(ttlMillis, 86400000L)) * 1000000L));
            while (entries.size() > maximumSize) {
                Iterator<K> iterator = entries.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            return true;
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private static final class Entry<V> {
        private final V value;
        private final long createdAt;
        private final long ttlNanos;

        private Entry(V value, long createdAt, long ttlNanos) {
            this.value = value;
            this.createdAt = createdAt;
            this.ttlNanos = ttlNanos;
        }
    }
}
