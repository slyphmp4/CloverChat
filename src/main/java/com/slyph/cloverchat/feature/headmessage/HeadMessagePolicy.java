package com.slyph.cloverchat.feature.headmessage;

public final class HeadMessagePolicy {

    private HeadMessagePolicy() {
    }

    public static boolean allows(String chatMode) {
        return "LOCAL".equals(chatMode) || "GLOBAL".equals(chatMode);
    }
}
