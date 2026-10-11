package com.slyph.cloverchat.feature.messagestyle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MessageStyleColorParserTest {

    @Test
    void parsesSupportedColorFormats() {
        assertEquals(0xCBFFF7, MessageStyleColorParser.parse("&#CBFFF7"));
        assertEquals(0xCBFFF7, MessageStyleColorParser.parse("&CBFFF7"));
        assertEquals(0xCBFFF7, MessageStyleColorParser.parse("#CBFFF7"));
        assertEquals(0x55FF55, MessageStyleColorParser.parse("&a"));
        assertEquals(0xFFAA00, MessageStyleColorParser.parse("gold"));
        assertNull(MessageStyleColorParser.parse("none"));
        assertNull(MessageStyleColorParser.parse("not-a-color"));
    }
}
