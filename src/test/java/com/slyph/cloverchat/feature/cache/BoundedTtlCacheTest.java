package com.slyph.cloverchat.feature.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class BoundedTtlCacheTest {

    @Test
    void expiresAndDoesNotExtendTtlOnRead() {
        AtomicLong time = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        BoundedTtlCache<String, Integer> cache = new BoundedTtlCache<>(2, time::get);
        assertEquals(1, cache.get("a", 10, calls::incrementAndGet));
        time.set(9000000);
        assertEquals(1, cache.get("a", 10, calls::incrementAndGet));
        time.set(10000000);
        assertEquals(2, cache.get("a", 10, calls::incrementAndGet));
    }

    @Test
    void disabledCacheAlwaysLoadsAndLruIsBounded() {
        AtomicInteger calls = new AtomicInteger();
        BoundedTtlCache<String, Integer> cache = new BoundedTtlCache<>(2);
        assertEquals(1, cache.get("a", 0, calls::incrementAndGet));
        assertEquals(2, cache.get("a", 0, calls::incrementAndGet));
        assertEquals(0, cache.size());
        cache.get("a", 10000, () -> 1);
        cache.get("b", 10000, () -> 2);
        cache.get("a", 10000, () -> 9);
        cache.get("c", 10000, () -> 3);
        assertEquals(2, cache.size());
        assertEquals(4, cache.get("b", 10000, () -> 4));
    }

    @Test
    void replayMarkIsAtomicAndExpires() {
        AtomicLong time = new AtomicLong();
        BoundedTtlCache<String, Boolean> cache = new BoundedTtlCache<>(2, time::get);
        AtomicInteger accepted = new AtomicInteger();
        IntStream.range(0, 100).parallel().forEach(i -> {
            if (cache.markIfAbsent("id", 10, true)) {
                accepted.incrementAndGet();
            }
        });
        assertEquals(1, accepted.get());
        time.set(10000000);
        assertTrue(cache.markIfAbsent("id", 10, true));
    }
}
