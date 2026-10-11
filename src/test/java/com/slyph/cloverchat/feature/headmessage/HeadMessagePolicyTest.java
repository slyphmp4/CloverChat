package com.slyph.cloverchat.feature.headmessage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HeadMessagePolicyTest {

    @Test
    void onlyPublicChannelsCanCreateHeadMessages() {
        assertTrue(HeadMessagePolicy.allows("LOCAL"));
        assertTrue(HeadMessagePolicy.allows("GLOBAL"));
        assertFalse(HeadMessagePolicy.allows("GROUP"));
        assertFalse(HeadMessagePolicy.allows("PRIVATE"));
        assertFalse(HeadMessagePolicy.allows("UNKNOWN"));
        assertFalse(HeadMessagePolicy.allows(null));
    }
}
