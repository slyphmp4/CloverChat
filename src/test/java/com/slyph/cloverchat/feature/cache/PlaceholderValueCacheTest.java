package com.slyph.cloverchat.feature.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class PlaceholderValueCacheTest {

    @Test
    void separatesPlayersAndCachesIndividualPlaceholders() {
        PlaceholderValueCache cache = new PlaceholderValueCache(100, 10000, Map.of());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("1 / 1", cache.resolve(first, "%test% / %test%", token -> "" + calls.incrementAndGet()));
        assertEquals("2", cache.resolve(second, "%test%", token -> "" + calls.incrementAndGet()));
        assertEquals("ID-new: 1", cache.resolve(first, "ID-new: %test%", token -> "wrong"));
        cache.invalidatePlayer(first);
        assertEquals("3", cache.resolve(first, "%test%", token -> "" + calls.incrementAndGet()));
    }

    @Test
    void replacementsAreExactAndZeroTtlAlwaysRefreshes() {
        PlaceholderValueCache cache = new PlaceholderValueCache(10, 10000,
                Map.of("%rep%", new PlaceholderValueCache.Rule(0, Map.of("0", "&7none", "x", "$\\"))));
        UUID player = UUID.randomUUID();
        assertEquals("&7none", cache.resolve(player, "%rep%", token -> "0"));
        assertEquals("10", cache.resolve(player, "%rep%", token -> "10"));
        assertEquals("$\\", cache.resolve(player, "%rep%", token -> "x"));
    }

    @Test
    void unresolvedPlaceholdersAreNotCached() {
        PlaceholderValueCache cache = new PlaceholderValueCache(10, 10000, Map.of());
        UUID player = UUID.randomUUID();
        assertEquals("%missing%", cache.resolve(player, "%missing%", token -> token));
        assertEquals("available", cache.resolve(player, "%missing%", token -> "available"));
    }
}
