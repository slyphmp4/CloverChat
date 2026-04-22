package com.slyph.cloverchat.listener;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.feature.messageinspect.model.ChatMessageAuditRecord;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ChatListener implements Listener {

    private static final Pattern LINK_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,16})");
    private static final AtomicLong MESSAGE_COUNTER = new AtomicLong(0L);
    private static final GsonComponentSerializer MESSAGE_SERIALIZER = GsonComponentSerializer.gson();

    private final CloverChatPlugin plugin;

    public ChatListener(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        event.setCancelled(true);
        handleIncomingChat(event.getPlayer().getUniqueId(), event.getMessage());
    }

    public void handleIncomingChat(UUID senderId, String originalMessage) {
        plugin.scheduler().runGlobal(() -> {
            Player sender = Bukkit.getPlayer(senderId);
            if (sender == null || !sender.isOnline()) {
                return;
            }
            plugin.scheduler().runEntity(sender, () -> handleChat(senderId, originalMessage));
        });
    }

    private void handleChat(UUID senderId, String originalMessage) {
        Player sender = Bukkit.getPlayer(senderId);
        if (sender == null || !sender.isOnline()) {
            return;
        }

        if (!sender.hasPermission("cloverchat.chat.use")) {
            List<String> noPermissionLines = plugin.messages().getStringList("no-chat-permission-message");
            if (noPermissionLines.isEmpty()) {
                noPermissionLines = Arrays.asList("&7", "&cУ вас нет права писать в чат", "&7");
            }
            plugin.sendConfiguredLines(sender, sender, noPermissionLines);
            return;
        }

        ChatRoute chatRoute = resolveChatRoute(sender, originalMessage);
        if (chatRoute.blocked) {
            return;
        }

        String chatMessage = chatRoute.prefixLength > 0 && originalMessage.length() >= chatRoute.prefixLength
                ? originalMessage.substring(chatRoute.prefixLength)
                : originalMessage;
        if (chatMessage.isBlank()) {
            return;
        }

        chatMessage = censorMessage(chatMessage);
        MentionResult mentionResult = processMentions(chatMessage);
        chatMessage = mentionResult.formatted;
        String messageTime = buildMessageTime();
        String messageId = buildMessageId();

        String playerToken = "__cloverchat_player_name__";
        String prefixToken = "__cloverchat_prefix__";
        String reputationToken = "__cloverchat_reputation__";
        String messageToken = "__cloverchat_message__";
        String format = chatRoute.format;

        String resolvedDisplayName = resolveDisplayName(sender, format);
        String resolvedPrefix = resolveUltraPermissionsPrefix(sender, format);
        String resolvedReputation = resolveReputationValue(sender, format);
        String resolvedGroup = resolveUltraPermissionsGroup(sender);
        format = replaceNamePlaceholders(format, playerToken);
        format = format.replace("%uperms_prefix%", prefixToken);
        format = replaceReputationPlaceholders(format, reputationToken);
        format = format
                .replace("%message%", messageToken);

        format = plugin.applyPlaceholders(sender, format);
        if (resolvedPrefix.isBlank()) {
            format = format.replace(prefixToken, "");
        }
        if (resolvedReputation.isBlank()) {
            format = format.replace(reputationToken, "");
        }

        Component finalMessage = buildFinalComponent(
                format,
                sender,
                playerToken,
                prefixToken,
                reputationToken,
                messageToken,
                resolvedPrefix,
                resolvedDisplayName,
                resolvedReputation,
                chatMessage,
                chatRoute.chatTypeName,
                messageTime,
                messageId
        );
        dispatchMessage(sender, chatRoute, finalMessage);
        plugin.proxyChatSyncService().forwardMessage(sender, messageId, chatRoute.mode.name(), chatRoute.viewPermission, finalMessage);
        plugin.headMessageService().show(sender, chatMessage);
        plugin.messageAuditService().trackMessage(buildAuditRecord(
                sender,
                originalMessage,
                chatMessage,
                finalMessage,
                chatRoute,
                messageId,
                messageTime,
                resolvedDisplayName,
                resolvedGroup,
                resolvedPrefix,
                resolvedReputation
        ));
        playMentionSound(mentionResult.mentionedPlayers);
    }

    private ChatMessageAuditRecord buildAuditRecord(
            Player sender,
            String rawInput,
            String finalText,
            Component finalMessageComponent,
            ChatRoute route,
            String messageId,
            String messageTime,
            String displayName,
            String groupName,
            String prefix,
            String reputation
    ) {
        Location location = sender.getLocation();
        String worldName = location.getWorld() == null ? "" : location.getWorld().getName();
        String groupChannel = route.mode == ChatMode.GROUP ? route.chatTypeName : "";
        String finalJson;
        try {
            finalJson = MESSAGE_SERIALIZER.serialize(finalMessageComponent);
        } catch (Exception ignored) {
            finalJson = "";
        }

        return new ChatMessageAuditRecord(
                messageId,
                System.currentTimeMillis(),
                messageTime,
                resolveServerId(),
                sender.getUniqueId().toString(),
                sender.getName(),
                displayName,
                groupName,
                prefix,
                reputation,
                route.mode.name(),
                route.chatTypeName,
                groupChannel,
                worldName,
                location.getX(),
                location.getY(),
                location.getZ(),
                rawInput,
                finalText,
                finalJson,
                route.viewPermission
        );
    }

    private String resolveServerId() {
        String value = plugin.configuration().getString("proxy-sync.server-id", "server");
        if (value == null || value.isBlank()) {
            return "server";
        }
        return value;
    }

    private void dispatchMessage(Player sender, ChatRoute chatRoute, Component message) {
        if (chatRoute.mode == ChatMode.GLOBAL) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                onlinePlayer.sendMessage(message);
            }
            return;
        }

        if (chatRoute.mode == ChatMode.GROUP) {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.getUniqueId().equals(sender.getUniqueId())) {
                    onlinePlayer.sendMessage(message);
                    continue;
                }
                if (!chatRoute.viewPermission.isBlank() && !onlinePlayer.hasPermission(chatRoute.viewPermission)) {
                    continue;
                }
                onlinePlayer.sendMessage(message);
            }
            return;
        }

        double radius = plugin.configuration().getDouble("local-chat.radius", 70.0);
        double radiusSquared = radius * radius;
        Location senderLocation = sender.getLocation();

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (!onlinePlayer.getWorld().equals(senderLocation.getWorld())) {
                continue;
            }
            if (onlinePlayer.getLocation().distanceSquared(senderLocation) <= radiusSquared) {
                onlinePlayer.sendMessage(message);
            }
        }
    }

    private Component buildFinalComponent(
            String input,
            Player sender,
            String playerToken,
            String prefixToken,
            String reputationToken,
            String messageToken,
            String resolvedPrefix,
            String resolvedDisplayName,
            String resolvedReputation,
            String messageText,
            String chatTypeName,
            String messageTime,
            String messageId
    ) {
        Component result = Component.empty();
        int currentIndex = 0;

        while (currentIndex < input.length()) {
            int nextPlayerIndex = input.indexOf(playerToken, currentIndex);
            int nextPrefixIndex = input.indexOf(prefixToken, currentIndex);
            int nextReputationIndex = input.indexOf(reputationToken, currentIndex);
            int nextMessageIndex = input.indexOf(messageToken, currentIndex);

            int tokenStart = Integer.MAX_VALUE;
            String tokenType = "";

            if (nextPlayerIndex >= 0 && nextPlayerIndex < tokenStart) {
                tokenStart = nextPlayerIndex;
                tokenType = "player";
            }
            if (nextPrefixIndex >= 0 && nextPrefixIndex < tokenStart) {
                tokenStart = nextPrefixIndex;
                tokenType = "prefix";
            }
            if (nextReputationIndex >= 0 && nextReputationIndex < tokenStart) {
                tokenStart = nextReputationIndex;
                tokenType = "reputation";
            }
            if (nextMessageIndex >= 0 && nextMessageIndex < tokenStart) {
                tokenStart = nextMessageIndex;
                tokenType = "message";
            }

            if (tokenType.isEmpty()) {
                break;
            }

            String before = input.substring(currentIndex, tokenStart);
            if (!before.isEmpty()) {
                result = result.append(parseLinksWithColoredText(before));
            }

            if (tokenType.equals("player")) {
                result = result.append(buildPlayerNameComponent(sender, resolvedDisplayName));
                currentIndex = tokenStart + playerToken.length();
            } else if (tokenType.equals("prefix")) {
                result = result.append(buildPrefixComponent(sender, resolvedPrefix));
                currentIndex = tokenStart + prefixToken.length();
            } else if (tokenType.equals("reputation")) {
                result = result.append(buildReputationComponent(sender, resolvedReputation));
                currentIndex = tokenStart + reputationToken.length();
            } else {
                result = result.append(buildChatMessageComponent(sender, messageText, chatTypeName, messageTime, messageId));
                currentIndex = tokenStart + messageToken.length();
            }
        }

        if (currentIndex < input.length()) {
            result = result.append(parseLinksWithColoredText(input.substring(currentIndex)));
        }

        return result;
    }

    private Component buildChatMessageComponent(Player sender, String messageText, String chatTypeName, String messageTime, String messageId) {
        String resolvedMessage = plugin.applyPlaceholders(sender, messageText == null ? "" : messageText);
        Component messageComponent = parseLinksWithColoredText(resolvedMessage);

        if (!plugin.configuration().getBoolean("message-hover.enabled", true)) {
            return messageComponent;
        }

        List<String> hoverLines = plugin.hovers().getStringList("message-hover.lines");
        if (hoverLines.isEmpty()) {
            hoverLines = plugin.hovers().getStringList("message-hover-text");
        }
        if (hoverLines.isEmpty()) {
            return messageComponent;
        }

        String plainMessage = extractPlainText(resolvedMessage);
        List<String> replaced = new ArrayList<>();
        for (String line : hoverLines) {
            String resolved = line
                    .replace("%player_name%", sender.getName())
                    .replace("%chat_type%", chatTypeName)
                    .replace("%message%", resolvedMessage)
                    .replace("%message_plain%", plainMessage)
                    .replace("%message_time%", messageTime)
                    .replace("%message_id%", messageId);
            replaced.add(plugin.applyPlaceholders(sender, resolved));
        }

        String hoverText = String.join("\n", replaced);
        messageComponent = messageComponent.hoverEvent(HoverEvent.showText(plugin.deserializeColored(hoverText)));

        if (plugin.configuration().getBoolean("message-hover.copy-id-on-click", true)) {
            messageComponent = messageComponent.clickEvent(ClickEvent.copyToClipboard(messageId));
        }

        return messageComponent;
    }

    private ChatRoute resolveChatRoute(Player sender, String originalMessage) {
        ChatRoute groupRoute = resolveGroupRoute(sender, originalMessage);
        if (groupRoute != null) {
            return groupRoute;
        }

        boolean globalEnabled = plugin.configuration().getBoolean("global-chat.enabled", true);
        String globalPrefix = plugin.configuration().getString("global-chat.prefix", "!");
        if (globalEnabled && globalPrefix != null && !globalPrefix.isBlank() && originalMessage.startsWith(globalPrefix)) {
            String format = plugin.messages().getString("global-chat.format", "&7[&6G&7] &f%player_name% &8» &f%message%");
            String chatTypeName = plugin.messages().getString("chat-types.global", "Global");
            return ChatRoute.global(globalPrefix.length(), format, chatTypeName);
        }

        if (!plugin.configuration().getBoolean("local-chat.enabled", true)) {
            return ChatRoute.blocked();
        }

        String format = plugin.messages().getString("local-chat.format", "&7[&aL&7] &f%player_name% &8» &f%message%");
        String chatTypeName = plugin.messages().getString("chat-types.local", "Local");
        return ChatRoute.local(format, chatTypeName);
    }

    private ChatRoute resolveGroupRoute(Player sender, String originalMessage) {
        if (!plugin.configuration().getBoolean("group-chat.enabled", false)) {
            return null;
        }

        ConfigurationSection channelsSection = plugin.configuration().getConfigurationSection("group-chat.channels");
        if (channelsSection == null) {
            return null;
        }

        GroupChannelCandidate matched = null;
        for (String channelKey : channelsSection.getKeys(false)) {
            ConfigurationSection channelSection = channelsSection.getConfigurationSection(channelKey);
            if (channelSection == null) {
                continue;
            }

            String prefix = channelSection.getString("prefix", "");
            if (prefix == null || prefix.isBlank()) {
                continue;
            }

            if (!originalMessage.startsWith(prefix)) {
                continue;
            }

            if (matched == null || prefix.length() > matched.prefix.length()) {
                matched = new GroupChannelCandidate(channelKey, prefix, channelSection);
            }
        }

        if (matched == null) {
            return null;
        }

        String channelDisplayName = matched.section.getString("display-name", matched.key);
        channelDisplayName = plugin.applyPlaceholders(sender, channelDisplayName);
        if (channelDisplayName == null || channelDisplayName.isBlank()) {
            channelDisplayName = matched.key;
        }

        if (!matched.section.getBoolean("enabled", true)) {
            List<String> disabledLines = plugin.messages().getStringList("group-chat.disabled-message");
            if (disabledLines.isEmpty()) {
                disabledLines = Arrays.asList("&7", "&cChannel %channel% is currently disabled", "&7");
            }
            sendChannelNotice(sender, disabledLines, channelDisplayName);
            return ChatRoute.blocked();
        }

        String sendPermission = matched.section.getString("send-permission", "");
        if (sendPermission != null && !sendPermission.isBlank() && !sender.hasPermission(sendPermission)) {
            List<String> noPermissionLines = plugin.messages().getStringList("group-chat.no-permission-message");
            if (noPermissionLines.isEmpty()) {
                noPermissionLines = Arrays.asList("&7", "&cYou do not have permission to write to %channel%", "&7");
            }
            sendChannelNotice(sender, noPermissionLines, channelDisplayName);
            return ChatRoute.blocked();
        }

        String defaultFormat = plugin.messages().getString("group-chat.default-format", "&7[&d%channel%&7] &f%player_name% &8» &f%message%");
        String format = plugin.messages().getString("group-chat.formats." + matched.key, defaultFormat);
        if (format == null || format.isBlank()) {
            format = defaultFormat;
        }
        format = format.replace("%channel%", channelDisplayName);

        String viewPermission = matched.section.getString("view-permission", sendPermission == null ? "" : sendPermission);
        if (viewPermission == null) {
            viewPermission = "";
        }
        return ChatRoute.group(matched.prefix.length(), format, channelDisplayName, viewPermission);
    }

    private void sendChannelNotice(Player player, List<String> lines, String channelName) {
        for (String line : lines) {
            String resolved = line.replace("%channel%", channelName);
            String withPlaceholders = plugin.applyPlaceholders(player, resolved);
            player.sendMessage(plugin.deserializeColored(withPlaceholders));
        }
    }

    private Component buildPlayerNameComponent(Player sender, String displayName) {
        String senderName = sender.getName();
        String shownName = displayName == null || displayName.isBlank() ? senderName : displayName;
        Component nameComponent = plugin.deserializeColored(shownName);

        List<String> hoverLines = plugin.hovers().getStringList("hover-text");
        if (hoverLines.isEmpty()) {
            return nameComponent;
        }

        List<String> replaced = new ArrayList<>();
        for (String line : hoverLines) {
            String resolved = line.replace("%player_name%", senderName);
            replaced.add(plugin.applyPlaceholders(sender, resolved));
        }

        String hoverText = String.join("\n", replaced);
        return nameComponent
                .hoverEvent(HoverEvent.showText(plugin.deserializeColored(hoverText)))
                .clickEvent(ClickEvent.suggestCommand("/m " + senderName + " "));
    }

    private String replaceNamePlaceholders(String format, String playerToken) {
        String replaced = format;
        for (String placeholder : getNamePlaceholders()) {
            if (placeholder != null && !placeholder.isBlank()) {
                replaced = replaced.replace(placeholder, playerToken);
            }
        }
        return replaced;
    }

    private String resolveDisplayName(Player sender, String format) {
        String selectedPlaceholder = null;
        for (String placeholder : getNamePlaceholders()) {
            if (placeholder != null && !placeholder.isBlank() && format.contains(placeholder)) {
                selectedPlaceholder = placeholder;
                break;
            }
        }

        if (selectedPlaceholder == null || selectedPlaceholder.equals("%player_name%")) {
            return sender.getName();
        }

        String resolved = plugin.applyPlaceholders(sender, selectedPlaceholder);
        if (resolved == null || resolved.isBlank() || resolved.equalsIgnoreCase(selectedPlaceholder)) {
            return sender.getName();
        }

        return resolved;
    }

    private List<String> getNamePlaceholders() {
        List<String> configured = plugin.configuration().getStringList("chat-name-placeholders");
        if (!configured.isEmpty()) {
            return configured;
        }
        return Arrays.asList("%player_name%", "%cloverrep_nick%");
    }

    private String replaceReputationPlaceholders(String format, String reputationToken) {
        String replaced = format;
        for (String placeholder : getReputationPlaceholders()) {
            if (placeholder != null && !placeholder.isBlank()) {
                replaced = replaced.replace(placeholder, reputationToken);
            }
        }
        return replaced;
    }

    private String resolveReputationValue(Player sender, String format) {
        String selectedPlaceholder = null;
        for (String placeholder : getReputationPlaceholders()) {
            if (placeholder != null && !placeholder.isBlank() && format.contains(placeholder)) {
                selectedPlaceholder = placeholder;
                break;
            }
        }

        if (selectedPlaceholder == null) {
            return "";
        }

        String resolved = plugin.applyPlaceholders(sender, selectedPlaceholder);
        if (resolved == null || resolved.isBlank() || resolved.equalsIgnoreCase(selectedPlaceholder)) {
            return "";
        }
        return resolved;
    }

    private List<String> getReputationPlaceholders() {
        List<String> configured = plugin.configuration().getStringList("chat-reputation-placeholders");
        if (!configured.isEmpty()) {
            return configured;
        }
        return List.of("%cloverrep_reputation%");
    }

    private Component buildPrefixComponent(Player sender, String prefixText) {
        if (prefixText == null || prefixText.isBlank()) {
            return Component.empty();
        }

        Component prefixComponent = plugin.deserializeColored(prefixText);
        List<String> hoverLines = resolvePrefixHoverLines(sender, prefixText);
        if (hoverLines.isEmpty()) {
            return prefixComponent;
        }

        List<String> replaced = new ArrayList<>();
        for (String line : hoverLines) {
            String resolved = line.replace("%prefix%", prefixText);
            replaced.add(plugin.applyPlaceholders(sender, resolved));
        }

        String hoverText = String.join("\n", replaced);
        return prefixComponent.hoverEvent(HoverEvent.showText(plugin.deserializeColored(hoverText)));
    }

    private Component buildReputationComponent(Player sender, String reputationText) {
        if (reputationText == null || reputationText.isBlank()) {
            return Component.empty();
        }

        Component reputationComponent = plugin.deserializeColored(reputationText);
        if (!plugin.configuration().getBoolean("reputation-hover.enabled", true)) {
            return reputationComponent;
        }

        List<String> hoverLines = plugin.hovers().getStringList("reputation-hover.lines");
        if (!hoverLines.isEmpty()) {
            List<String> replaced = new ArrayList<>();
            for (String line : hoverLines) {
                String resolved = line
                        .replace("%player_name%", sender.getName())
                        .replace("%reputation%", reputationText);
                replaced.add(plugin.applyPlaceholders(sender, resolved));
            }
            String hoverText = String.join("\n", replaced);
            reputationComponent = reputationComponent.hoverEvent(HoverEvent.showText(plugin.deserializeColored(hoverText)));
        }

        String suggestCommand = plugin.configuration().getString("reputation-hover.suggest-command", "/rep ");
        if (suggestCommand != null && !suggestCommand.isBlank()) {
            reputationComponent = reputationComponent.clickEvent(ClickEvent.suggestCommand(suggestCommand));
        }

        return reputationComponent;
    }

    private List<String> resolvePrefixHoverLines(Player sender, String prefixText) {
        if (!plugin.configuration().getBoolean("prefix-hover.enabled", true)) {
            return List.of();
        }

        ConfigurationSection rulesSection = plugin.hovers().getConfigurationSection("prefix-hover.rules");
        if (rulesSection != null) {
            List<ConfigurationSection> rules = new ArrayList<>();
            for (String ruleKey : rulesSection.getKeys(false)) {
                ConfigurationSection ruleSection = rulesSection.getConfigurationSection(ruleKey);
                if (ruleSection != null) {
                    rules.add(ruleSection);
                }
            }

            rules.sort(Comparator.comparingInt((ConfigurationSection section) -> section.getInt("priority", 0)).reversed());

            String group = resolveUltraPermissionsGroup(sender);
            for (ConfigurationSection rule : rules) {
                if (matchesPrefixRule(rule, sender, prefixText, group)) {
                    List<String> lines = rule.getStringList("lines");
                    if (!lines.isEmpty()) {
                        return lines;
                    }
                }
            }
        }

        List<String> defaultLines = plugin.hovers().getStringList("prefix-hover.default-lines");
        if (!defaultLines.isEmpty()) {
            return defaultLines;
        }

        return plugin.hovers().getStringList("prefix-hover-text");
    }

    private boolean matchesPrefixRule(ConfigurationSection rule, Player sender, String prefixText, String group) {
        String matchType = rule.getString("match-type", "contains").toLowerCase();
        String match = rule.getString("match", "");
        boolean ignoreCase = rule.getBoolean("ignore-case", true);

        String rawPrefix = prefixText == null ? "" : prefixText;
        String visiblePrefix = extractVisiblePrefix(prefixText);
        String normalizedGroup = group == null ? "" : group;

        if (matchType.equals("group")) {
            return equalsValue(normalizedGroup, match, ignoreCase);
        }

        if (matchType.equals("group-contains")) {
            return containsValue(normalizedGroup, match, ignoreCase);
        }

        if (matchType.equals("permission")) {
            String permission = rule.getString("permission", match);
            return permission != null && !permission.isBlank() && hasPermission(sender, permission);
        }

        if (matchType.equals("permission-any")) {
            List<String> permissions = readPermissions(rule);
            return hasAnyPermission(sender, permissions);
        }

        if (matchType.equals("permission-all")) {
            List<String> permissions = readPermissions(rule);
            return hasAllPermissions(sender, permissions);
        }

        if (matchType.equals("equals")) {
            return equalsValue(visiblePrefix, match, ignoreCase) || equalsValue(rawPrefix, match, ignoreCase);
        }

        if (matchType.equals("regex")) {
            return regexMatches(visiblePrefix, match, ignoreCase) || regexMatches(rawPrefix, match, ignoreCase);
        }

        return containsValue(visiblePrefix, match, ignoreCase) || containsValue(rawPrefix, match, ignoreCase);
    }

    private List<String> readPermissions(ConfigurationSection rule) {
        List<String> permissions = new ArrayList<>(rule.getStringList("permissions"));
        String single = rule.getString("permission", "");
        if (!single.isBlank()) {
            permissions.add(single);
        }
        return permissions;
    }

    private boolean hasAnyPermission(Player sender, List<String> permissions) {
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && hasPermission(sender, permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAllPermissions(Player sender, List<String> permissions) {
        if (permissions.isEmpty()) {
            return false;
        }
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            if (!hasPermission(sender, permission)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPermission(Player sender, String permission) {
        return sender != null && sender.hasPermission(permission);
    }

    private boolean equalsValue(String input, String expected, boolean ignoreCase) {
        if (expected == null) {
            return false;
        }
        if (ignoreCase) {
            return input.equalsIgnoreCase(expected);
        }
        return input.equals(expected);
    }

    private boolean containsValue(String input, String expected, boolean ignoreCase) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        if (ignoreCase) {
            return input.toLowerCase().contains(expected.toLowerCase());
        }
        return input.contains(expected);
    }

    private boolean regexMatches(String input, String regex, boolean ignoreCase) {
        if (regex == null || regex.isBlank()) {
            return false;
        }

        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        try {
            return Pattern.compile(regex, flags).matcher(input).find();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractVisiblePrefix(String prefixText) {
        String colored = plugin.applyColor(prefixText == null ? "" : prefixText);
        String stripped = ChatColor.stripColor(colored);
        if (stripped == null) {
            return colored;
        }
        return stripped;
    }

    private String extractPlainText(String text) {
        String colored = plugin.applyColor(text == null ? "" : text);
        String stripped = ChatColor.stripColor(colored);
        if (stripped == null) {
            return colored;
        }
        return stripped;
    }

    private String buildMessageTime() {
        String pattern = plugin.configuration().getString("message-hover.time-format", "HH:mm:ss");
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (Exception ignored) {
            formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        }
        return LocalDateTime.now().format(formatter);
    }

    private String buildMessageId() {
        String timePart = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        String counterPart = Long.toString(MESSAGE_COUNTER.incrementAndGet(), 36).toUpperCase(Locale.ROOT);
        return timePart + "-" + counterPart;
    }

    private String resolveUltraPermissionsGroup(Player sender) {
        if (!plugin.isPlaceholderApiHooked()) {
            return "";
        }
        String resolved = plugin.applyPlaceholders(sender, "%uperms_group%");
        if (resolved == null || resolved.equalsIgnoreCase("%uperms_group%")) {
            return "";
        }
        return resolved;
    }

    private String resolveUltraPermissionsPrefix(Player sender, String format) {
        if (format == null || !format.contains("%uperms_prefix%")) {
            return "";
        }
        if (!plugin.isPlaceholderApiHooked()) {
            return "";
        }
        String resolved = plugin.applyPlaceholders(sender, "%uperms_prefix%");
        if (resolved == null || resolved.equalsIgnoreCase("%uperms_prefix%")) {
            return "";
        }
        return resolved;
    }

    private Component parseLinksWithColoredText(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        if (!plugin.configuration().getBoolean("links.enabled", true)) {
            return plugin.deserializeColored(input);
        }

        Matcher matcher = LINK_PATTERN.matcher(input);
        Component result = Component.empty();
        int lastEnd = 0;

        while (matcher.find()) {
            String beforeLink = input.substring(lastEnd, matcher.start());
            String url = matcher.group(1);

            if (!beforeLink.isEmpty()) {
                result = result.append(plugin.deserializeColored(beforeLink));
            }

            String linkFormat = plugin.messages().getString("links.format", "&e*ссылка*");
            Component linkComponent = plugin.deserializeColored(linkFormat)
                    .clickEvent(ClickEvent.openUrl(url));

            List<String> hoverLines = plugin.hovers().getStringList("links.hover-lines");
            if (hoverLines.isEmpty()) {
                hoverLines = plugin.hovers().getStringList("links.hover");
            }

            if (!hoverLines.isEmpty()) {
                List<String> replacedHover = new ArrayList<>();
                for (String line : hoverLines) {
                    replacedHover.add(line.replace("%url%", url));
                }
                String hoverText = String.join("\n", replacedHover);
                linkComponent = linkComponent.hoverEvent(HoverEvent.showText(plugin.deserializeColored(hoverText)));
            }

            result = result.append(linkComponent);
            lastEnd = matcher.end();
        }

        if (lastEnd < input.length()) {
            result = result.append(plugin.deserializeColored(input.substring(lastEnd)));
        }

        return result;
    }

    private MentionResult processMentions(String text) {
        Matcher matcher = MENTION_PATTERN.matcher(text);
        String mentionFormat = plugin.messages().getString("mention.highlight-format", "&6@%mention%");
        Set<Player> mentionedPlayers = new LinkedHashSet<>();
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String mentionName = matcher.group(1);
            Player target = findOnlinePlayer(mentionName);

            if (target != null) {
                mentionedPlayers.add(target);
                String replacement = mentionFormat.replace("%mention%", target.getName());
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }

        matcher.appendTail(result);
        return new MentionResult(result.toString(), mentionedPlayers);
    }

    private Player findOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && exact.isOnline()) {
            return exact;
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.getName().equalsIgnoreCase(name)) {
                return onlinePlayer;
            }
        }

        return null;
    }

    private void playMentionSound(Set<Player> mentionedPlayers) {
        if (mentionedPlayers.isEmpty()) {
            return;
        }

        String soundKey = plugin.configuration().getString("mention.sound", "minecraft:block.note_block.pling");
        Sound sound;

        try {
            sound = Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
            sound = Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.PLAYER, 1.0f, 1.0f);
        }

        for (Player player : mentionedPlayers) {
            if (player.isOnline()) {
                player.playSound(sound);
            }
        }
    }

    private String censorMessage(String message) {
        if (!plugin.configuration().getBoolean("censor.enabled", true)) {
            return message;
        }

        List<String> badWords = plugin.configuration().getStringList("censor.words");
        if (badWords.isEmpty()) {
            return message;
        }

        String result = message;

        for (String badWord : badWords) {
            if (badWord == null || badWord.isBlank()) {
                continue;
            }

            Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(badWord) + "(?![\\p{L}\\p{N}_])");
            Matcher matcher = pattern.matcher(result);
            StringBuffer replaced = new StringBuffer();

            while (matcher.find()) {
                String found = matcher.group();
                String masked = maskWord(found);
                matcher.appendReplacement(replaced, Matcher.quoteReplacement(masked));
            }

            matcher.appendTail(replaced);
            result = replaced.toString();
        }

        return result;
    }

    private String maskWord(String word) {
        if (word.length() <= 2) {
            return "*".repeat(word.length());
        }

        StringBuilder builder = new StringBuilder();
        builder.append(word.charAt(0));

        for (int index = 1; index < word.length() - 1; index++) {
            builder.append('*');
        }

        builder.append(word.charAt(word.length() - 1));
        return builder.toString();
    }

    private enum ChatMode {
        LOCAL,
        GLOBAL,
        GROUP
    }

    private static final class ChatRoute {
        private final ChatMode mode;
        private final boolean blocked;
        private final int prefixLength;
        private final String format;
        private final String chatTypeName;
        private final String viewPermission;

        private ChatRoute(ChatMode mode, boolean blocked, int prefixLength, String format, String chatTypeName, String viewPermission) {
            this.mode = mode;
            this.blocked = blocked;
            this.prefixLength = prefixLength;
            this.format = format;
            this.chatTypeName = chatTypeName;
            this.viewPermission = viewPermission;
        }

        private static ChatRoute blocked() {
            return new ChatRoute(ChatMode.LOCAL, true, 0, "", "", "");
        }

        private static ChatRoute local(String format, String chatTypeName) {
            return new ChatRoute(ChatMode.LOCAL, false, 0, format, chatTypeName, "");
        }

        private static ChatRoute global(int prefixLength, String format, String chatTypeName) {
            return new ChatRoute(ChatMode.GLOBAL, false, prefixLength, format, chatTypeName, "");
        }

        private static ChatRoute group(int prefixLength, String format, String chatTypeName, String viewPermission) {
            return new ChatRoute(ChatMode.GROUP, false, prefixLength, format, chatTypeName, viewPermission);
        }
    }

    private static final class GroupChannelCandidate {
        private final String key;
        private final String prefix;
        private final ConfigurationSection section;

        private GroupChannelCandidate(String key, String prefix, ConfigurationSection section) {
            this.key = key;
            this.prefix = prefix;
            this.section = section;
        }
    }

    private static final class MentionResult {

        private final String formatted;
        private final Set<Player> mentionedPlayers;

        private MentionResult(String formatted, Set<Player> mentionedPlayers) {
            this.formatted = formatted;
            this.mentionedPlayers = mentionedPlayers;
        }
    }
}
