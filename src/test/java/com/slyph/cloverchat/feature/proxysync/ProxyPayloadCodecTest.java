package com.slyph.cloverchat.feature.proxysync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyPayloadCodecTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsValidSignedPayload() {
        ProxyPayloadCodec codec = new ProxyPayloadCodec(SECRET);
        long now = System.currentTimeMillis();
        ProxyPayloadCodec.Payload source = new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                now,
                "survival",
                "survival-550E8400-E29B-41D4-A716-446655440000",
                "GLOBAL",
                "",
                "{\"text\":\"hello\"}"
        );

        ProxyPayloadCodec.Payload decoded = codec.decode(codec.encode(source), now, 45000L);

        assertEquals(source.messageId(), decoded.messageId());
        assertEquals(source.componentJson(), decoded.componentJson());
    }

    @Test
    void rejectsTamperedPayload() {
        ProxyPayloadCodec codec = new ProxyPayloadCodec(SECRET);
        long now = System.currentTimeMillis();
        byte[] encoded = codec.encode(new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                now,
                "survival",
                "survival-550E8400-E29B-41D4-A716-446655440000",
                "GLOBAL",
                "",
                "{\"text\":\"hello\"}"
        ));
        encoded[encoded.length - 1] ^= 1;

        assertNull(codec.decode(encoded, now, 45000L));
    }

    @Test
    void rejectsExpiredPayload() {
        ProxyPayloadCodec codec = new ProxyPayloadCodec(SECRET);
        long now = System.currentTimeMillis();
        byte[] encoded = codec.encode(new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                now - 60000L,
                "survival",
                "survival-550E8400-E29B-41D4-A716-446655440000",
                "GLOBAL",
                "",
                "{\"text\":\"hello\"}"
        ));

        assertNull(codec.decode(encoded, now, 45000L));
    }

    @Test
    void rejectsPayloadFromFarFuture() {
        ProxyPayloadCodec codec = new ProxyPayloadCodec(SECRET);
        long now = System.currentTimeMillis();
        byte[] encoded = codec.encode(new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                now + 15000L,
                "survival",
                "survival-550E8400-E29B-41D4-A716-446655440000",
                "GLOBAL",
                "",
                "{\"text\":\"hello\"}"
        ));

        assertNull(codec.decode(encoded, now, 45000L));
    }

    @Test
    void rejectsNonPositiveTimestamp() {
        ProxyPayloadCodec codec = new ProxyPayloadCodec(SECRET);
        byte[] encoded = codec.encode(new ProxyPayloadCodec.Payload(
                ProxyPayloadCodec.VERSION,
                0L,
                "survival",
                "survival-550E8400-E29B-41D4-A716-446655440000",
                "GLOBAL",
                "",
                "{\"text\":\"hello\"}"
        ));

        assertNull(codec.decode(encoded, System.currentTimeMillis(), 45000L));
    }

    @Test
    void requiresStrongSecret() {
        assertFalse(ProxyPayloadCodec.isStrongSecret("change_me"));
        assertFalse(ProxyPayloadCodec.isStrongSecret("short"));
        assertTrue(ProxyPayloadCodec.isStrongSecret(SECRET));
        assertEquals(32, SECRET.getBytes(StandardCharsets.UTF_8).length);
    }
}
