package com.slyph.cloverchat.feature.messagestyle;

import java.util.Locale;

final class MessageStyleColorParser {

    private MessageStyleColorParser() {
    }

    static Integer parse(String input) {
        if (input == null) {
            return null;
        }

        String normalized = input.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("none")) {
            return null;
        }
        if (normalized.startsWith("&") || normalized.startsWith("§")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }

        if (normalized.matches("(?i)[0-9a-f]{6}")) {
            return Integer.parseInt(normalized, 16);
        }
        if (normalized.matches("(?i)[0-9a-f]")) {
            return legacyColorValue(normalized.charAt(0));
        }
        return namedColor(normalized);
    }

    private static int legacyColorValue(char code) {
        switch (Character.toLowerCase(code)) {
            case '0':
                return 0x000000;
            case '1':
                return 0x0000AA;
            case '2':
                return 0x00AA00;
            case '3':
                return 0x00AAAA;
            case '4':
                return 0xAA0000;
            case '5':
                return 0xAA00AA;
            case '6':
                return 0xFFAA00;
            case '7':
                return 0xAAAAAA;
            case '8':
                return 0x555555;
            case '9':
                return 0x5555FF;
            case 'a':
                return 0x55FF55;
            case 'b':
                return 0x55FFFF;
            case 'c':
                return 0xFF5555;
            case 'd':
                return 0xFF55FF;
            case 'e':
                return 0xFFFF55;
            default:
                return 0xFFFFFF;
        }
    }

    private static Integer namedColor(String input) {
        switch (input.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_')) {
            case "black":
                return 0x000000;
            case "dark_blue":
                return 0x0000AA;
            case "dark_green":
                return 0x00AA00;
            case "dark_aqua":
                return 0x00AAAA;
            case "dark_red":
                return 0xAA0000;
            case "dark_purple":
                return 0xAA00AA;
            case "gold":
                return 0xFFAA00;
            case "gray":
            case "grey":
                return 0xAAAAAA;
            case "dark_gray":
            case "dark_grey":
                return 0x555555;
            case "blue":
                return 0x5555FF;
            case "green":
                return 0x55FF55;
            case "aqua":
                return 0x55FFFF;
            case "red":
                return 0xFF5555;
            case "light_purple":
                return 0xFF55FF;
            case "yellow":
                return 0xFFFF55;
            case "white":
                return 0xFFFFFF;
            default:
                return null;
        }
    }
}
