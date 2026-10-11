package com.slyph.cloverchat.feature.cache;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderValueCache {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([^%\\r\\n]+)%");
    private final BoundedTtlCache<Key, String> cache;
    private final long defaultTtlMillis;
    private final Map<String, Rule> rules;

    public PlaceholderValueCache(int maximumSize, long defaultTtlMillis, Map<String, Rule> rules) {
        cache = new BoundedTtlCache<>(maximumSize);
        this.defaultTtlMillis = defaultTtlMillis;
        this.rules = Map.copyOf(rules);
    }

    public String resolve(UUID playerId, String text, Function<String, String> resolver) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group();
            Rule rule = rules.get(placeholder);
            long ttl = rule == null ? defaultTtlMillis : rule.ttlMillis;
            String value = cache.get(new Key(playerId, placeholder), ttl,
                    () -> resolver.apply(placeholder),
                    resolved -> resolved != null && !resolved.equals(placeholder));
            if (value == null) {
                value = placeholder;
            }
            if (rule != null) {
                value = rule.replacements.getOrDefault(value, value);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public void invalidatePlayer(UUID playerId) {
        cache.invalidateMatching(key -> key.playerId.equals(playerId));
    }

    public static final class Rule {
        private final long ttlMillis;
        private final Map<String, String> replacements;

        public Rule(long ttlMillis, Map<String, String> replacements) {
            this.ttlMillis = ttlMillis;
            this.replacements = Map.copyOf(replacements);
        }
    }

    private static final class Key {
        private final UUID playerId;
        private final String placeholder;

        private Key(UUID playerId, String placeholder) {
            this.playerId = playerId;
            this.placeholder = placeholder;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key key = (Key) other;
            return playerId.equals(key.playerId) && placeholder.equals(key.placeholder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, placeholder);
        }
    }
}
