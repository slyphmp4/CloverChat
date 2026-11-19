package com.slyph.cloverchat.listener;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernChatBridgeTest {

    @Test
    void serializesAdventureComponentsAsVisibleChatText() {
        Component richMessage = Component.text("Hello ").append(Component.text("26.3"));
        assertEquals("Hello 26.3", ModernChatBridge.resolveMessageText(richMessage));
    }

    @Test
    void supportsLegacyStringMessages() {
        assertEquals("legacy chat", ModernChatBridge.resolveMessageText("legacy chat"));
    }

    @Test
    void handlesNullMessageWithoutCrashing() {
        assertEquals("", ModernChatBridge.resolveMessageText(null));
    }
}
