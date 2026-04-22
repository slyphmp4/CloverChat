package com.slyph.cloverchat.feature.messageinspect.model;

public final class ChatMessageAuditRecord {

    private final String messageId;
    private final long createdAtMillis;
    private final String messageTime;
    private final String serverId;
    private final String playerUuid;
    private final String playerName;
    private final String playerDisplayName;
    private final String playerGroup;
    private final String playerPrefix;
    private final String playerReputation;
    private final String chatMode;
    private final String chatType;
    private final String groupChannel;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final String rawInput;
    private final String chatMessage;
    private final String finalMessageJson;
    private final String viewPermission;

    public ChatMessageAuditRecord(
            String messageId,
            long createdAtMillis,
            String messageTime,
            String serverId,
            String playerUuid,
            String playerName,
            String playerDisplayName,
            String playerGroup,
            String playerPrefix,
            String playerReputation,
            String chatMode,
            String chatType,
            String groupChannel,
            String worldName,
            double x,
            double y,
            double z,
            String rawInput,
            String chatMessage,
            String finalMessageJson,
            String viewPermission
    ) {
        this.messageId = safe(messageId);
        this.createdAtMillis = createdAtMillis;
        this.messageTime = safe(messageTime);
        this.serverId = safe(serverId);
        this.playerUuid = safe(playerUuid);
        this.playerName = safe(playerName);
        this.playerDisplayName = safe(playerDisplayName);
        this.playerGroup = safe(playerGroup);
        this.playerPrefix = safe(playerPrefix);
        this.playerReputation = safe(playerReputation);
        this.chatMode = safe(chatMode);
        this.chatType = safe(chatType);
        this.groupChannel = safe(groupChannel);
        this.worldName = safe(worldName);
        this.x = x;
        this.y = y;
        this.z = z;
        this.rawInput = safe(rawInput);
        this.chatMessage = safe(chatMessage);
        this.finalMessageJson = safe(finalMessageJson);
        this.viewPermission = safe(viewPermission);
    }

    public String messageId() {
        return messageId;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public String messageTime() {
        return messageTime;
    }

    public String serverId() {
        return serverId;
    }

    public String playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public String playerDisplayName() {
        return playerDisplayName;
    }

    public String playerGroup() {
        return playerGroup;
    }

    public String playerPrefix() {
        return playerPrefix;
    }

    public String playerReputation() {
        return playerReputation;
    }

    public String chatMode() {
        return chatMode;
    }

    public String chatType() {
        return chatType;
    }

    public String groupChannel() {
        return groupChannel;
    }

    public String worldName() {
        return worldName;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public String rawInput() {
        return rawInput;
    }

    public String chatMessage() {
        return chatMessage;
    }

    public String finalMessageJson() {
        return finalMessageJson;
    }

    public String viewPermission() {
        return viewPermission;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
