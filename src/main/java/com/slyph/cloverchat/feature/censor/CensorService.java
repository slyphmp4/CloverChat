package com.slyph.cloverchat.feature.censor;

import com.slyph.cloverchat.CloverChatPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CensorService {

    private static final String INVISIBLE_OR_COLOR = "(?:(?:&(?:#[0-9A-Fa-f]{6}|[0-9A-FK-ORa-fk-or])|§(?:x(?:§[0-9A-Fa-f]){6}|[0-9A-FK-ORa-fk-or]))|[\\u200B-\\u200D\\u2060\\uFEFF])*";
    private static final Pattern STRIP_INVISIBLE_OR_COLOR = Pattern.compile("(?:&(?:#[0-9A-Fa-f]{6}|[0-9A-FK-ORa-fk-or])|§(?:x(?:§[0-9A-Fa-f]){6}|[0-9A-FK-ORa-fk-or]))|[\\u200B-\\u200D\\u2060\\uFEFF]", Pattern.CASE_INSENSITIVE);

    private final CloverChatPlugin plugin;
    private volatile List<Pattern> patterns = List.of();

    public CensorService(CloverChatPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        patterns = compilePatterns(plugin.configuration().getStringList("censor.words"));
    }

    public String censor(String message) {
        if (message == null || message.isEmpty() || !plugin.configuration().getBoolean("censor.enabled", true)) {
            return message == null ? "" : message;
        }

        return censorWithPatterns(message, patterns);
    }

    static String censorWithPatterns(String message, List<Pattern> activePatterns) {
        String result = message == null ? "" : message;
        for (Pattern pattern : activePatterns) {
            Matcher matcher = pattern.matcher(result);
            StringBuffer replaced = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(mask(matcher.group())));
            }
            matcher.appendTail(replaced);
            result = replaced.toString();
        }
        return result;
    }

    static List<Pattern> compilePatterns(List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }

        List<Pattern> compiled = new ArrayList<>();
        for (String rawWord : words) {
            if (rawWord == null) {
                continue;
            }
            String word = STRIP_INVISIBLE_OR_COLOR.matcher(rawWord.trim()).replaceAll("");
            int[] codePoints = word.codePoints().toArray();
            if (codePoints.length == 0 || codePoints.length > 64 || compiled.size() >= 256) {
                continue;
            }

            StringBuilder expression = new StringBuilder("(?<![\\p{L}\\p{N}_])");
            for (int index = 0; index < codePoints.length; index++) {
                if (index > 0) {
                    expression.append(INVISIBLE_OR_COLOR);
                }
                expression.append(Pattern.quote(new String(Character.toChars(codePoints[index]))));
            }
            expression.append("(?![\\p{L}\\p{N}_])");
            compiled.add(Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(compiled);
    }

    static String mask(String value) {
        String visible = STRIP_INVISIBLE_OR_COLOR.matcher(value == null ? "" : value).replaceAll("");
        int[] codePoints = visible.codePoints().toArray();
        if (codePoints.length == 0) {
            return "";
        }
        if (codePoints.length <= 2) {
            return "*".repeat(codePoints.length);
        }

        StringBuilder result = new StringBuilder();
        result.appendCodePoint(codePoints[0]);
        result.append("*".repeat(codePoints.length - 2));
        result.appendCodePoint(codePoints[codePoints.length - 1]);
        return result.toString();
    }
}
