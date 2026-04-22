package com.slyph.cloverchat.feature.messageinspect;

import com.slyph.cloverchat.CloverChatPlugin;
import com.slyph.cloverchat.feature.messageinspect.model.ChatMessageAuditRecord;
import com.slyph.cloverchat.util.CompatScheduler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MessageAuditService {

    private static final String TABLE_NAME = "cloverchat_message_audit";
    private static final DateTimeFormatter INSPECT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CloverChatPlugin plugin;
    private final ConcurrentLinkedDeque<ChatMessageAuditRecord> writeQueue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ChatMessageAuditRecord> recentRecords = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentOrder = new ConcurrentLinkedDeque<>();
    private final Object datasourceLock = new Object();

    private CompatScheduler.TaskHandle flushTask;
    private CompatScheduler.TaskHandle healthTask;
    private volatile HikariDataSource dataSource;
    private volatile long lastConnectAttemptMillis;
    private volatile long lastConnectionErrorLogMillis;
    private volatile long lastInsertErrorLogMillis;
    private volatile long lastBackupErrorLogMillis;
    private volatile String activeProfileName = "LOCAL";

    public MessageAuditService(CloverChatPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!isEnabled()) {
            return;
        }

        ensureDataSource();
        long flushIntervalTicks = Math.max(20L, plugin.configuration().getLong("message-inspector.writer.flush-interval-ticks", 40L));
        long healthIntervalTicks = Math.max(200L, plugin.configuration().getLong("message-inspector.database.health-check-interval-ticks", 1200L));
        flushTask = plugin.scheduler().runAsyncRepeating(this::flushSafely, flushIntervalTicks, flushIntervalTicks);
        healthTask = plugin.scheduler().runAsyncRepeating(this::healthCheckSafely, healthIntervalTicks, healthIntervalTicks);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (healthTask != null) {
            healthTask.cancel();
            healthTask = null;
        }
        flushSafely();
        closeDataSource();
    }

    public boolean isEnabled() {
        return plugin.configuration().getBoolean("message-inspector.enabled", true);
    }

    public String activeProfileName() {
        return activeProfileName;
    }

    public void trackMessage(ChatMessageAuditRecord record) {
        if (!isEnabled() || record == null) {
            return;
        }
        if (record.messageId().isBlank()) {
            return;
        }

        cacheRecentRecord(record);
        enqueueRecord(record);
    }

    public List<String> completeMessageIds(String prefix, int limit) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        int max = Math.max(1, limit);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Iterator<String> iterator = recentOrder.descendingIterator();
        while (iterator.hasNext()) {
            String id = iterator.next();
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!normalized.isEmpty() && !id.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            result.add(id);
            if (result.size() >= max) {
                break;
            }
        }

        return result;
    }

    public void findByMessageIdAsync(
            String messageId,
            Consumer<ChatMessageAuditRecord> onResult,
            Consumer<Throwable> onError
    ) {
        if (onResult == null) {
            return;
        }

        String id = messageId == null ? "" : messageId.trim();
        if (id.isBlank()) {
            plugin.scheduler().runGlobal(() -> onResult.accept(null));
            return;
        }

        plugin.scheduler().runAsync(() -> {
            try {
                ChatMessageAuditRecord result = findByMessageId(id);
                plugin.scheduler().runGlobal(() -> onResult.accept(result));
            } catch (Throwable throwable) {
                if (onError != null) {
                    plugin.scheduler().runGlobal(() -> onError.accept(throwable));
                    return;
                }
                plugin.scheduler().runGlobal(() -> onResult.accept(null));
            }
        });
    }

    private ChatMessageAuditRecord findByMessageId(String messageId) {
        ChatMessageAuditRecord cached = recentRecords.get(messageId);
        if (cached != null) {
            return cached;
        }

        if (!ensureDataSource()) {
            return null;
        }

        String sql = "SELECT message_id, created_at, message_time, server_id, player_uuid, player_name, player_display_name, " +
                "player_group_name, player_prefix, player_reputation, chat_mode, chat_type_name, group_channel, world_name, " +
                "pos_x, pos_y, pos_z, raw_input, chat_message, final_message_json, view_permission " +
                "FROM " + TABLE_NAME + " WHERE message_id = ? LIMIT 1";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ChatMessageAuditRecord record = mapRecord(resultSet);
                cacheRecentRecord(record);
                return record;
            }
        } catch (Exception exception) {
            closeDataSource();
            logConnectionError("Message inspect query failed: " + exception.getMessage());
            return null;
        }
    }

    private ChatMessageAuditRecord mapRecord(ResultSet resultSet) throws Exception {
        return new ChatMessageAuditRecord(
                resultSet.getString("message_id"),
                resultSet.getLong("created_at"),
                resultSet.getString("message_time"),
                resultSet.getString("server_id"),
                resultSet.getString("player_uuid"),
                resultSet.getString("player_name"),
                resultSet.getString("player_display_name"),
                resultSet.getString("player_group_name"),
                resultSet.getString("player_prefix"),
                resultSet.getString("player_reputation"),
                resultSet.getString("chat_mode"),
                resultSet.getString("chat_type_name"),
                resultSet.getString("group_channel"),
                resultSet.getString("world_name"),
                resultSet.getDouble("pos_x"),
                resultSet.getDouble("pos_y"),
                resultSet.getDouble("pos_z"),
                resultSet.getString("raw_input"),
                resultSet.getString("chat_message"),
                resultSet.getString("final_message_json"),
                resultSet.getString("view_permission")
        );
    }

    public Map<String, String> buildPlaceholders(ChatMessageAuditRecord record) {
        String createdAt = INSPECT_TIME_FORMAT.format(
                Instant.ofEpochMilli(record.createdAtMillis()).atZone(ZoneId.systemDefault()).toLocalDateTime()
        );
        return Map.ofEntries(
                Map.entry("%message_id%", safe(record.messageId())),
                Map.entry("%message_time%", safe(record.messageTime())),
                Map.entry("%message_created_at%", safe(createdAt)),
                Map.entry("%server_id%", safe(record.serverId())),
                Map.entry("%player_name%", safe(record.playerName())),
                Map.entry("%player_uuid%", safe(record.playerUuid())),
                Map.entry("%player_display_name%", safe(record.playerDisplayName())),
                Map.entry("%player_group%", safe(record.playerGroup())),
                Map.entry("%player_prefix%", safe(record.playerPrefix())),
                Map.entry("%player_reputation%", safe(record.playerReputation())),
                Map.entry("%chat_mode%", safe(record.chatMode())),
                Map.entry("%chat_type%", safe(record.chatType())),
                Map.entry("%group_channel%", safe(record.groupChannel())),
                Map.entry("%world%", safe(record.worldName())),
                Map.entry("%x%", formatCoordinate(record.x())),
                Map.entry("%y%", formatCoordinate(record.y())),
                Map.entry("%z%", formatCoordinate(record.z())),
                Map.entry("%message_raw%", safe(record.rawInput())),
                Map.entry("%message_final%", safe(record.chatMessage())),
                Map.entry("%view_permission%", safe(record.viewPermission()))
        );
    }

    private String formatCoordinate(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void cacheRecentRecord(ChatMessageAuditRecord record) {
        String id = record.messageId();
        if (id.isBlank()) {
            return;
        }
        recentRecords.put(id, record);
        recentOrder.addLast(id);
        trimRecentCache();
    }

    private void trimRecentCache() {
        int limit = Math.max(50, plugin.configuration().getInt("message-inspector.cache.recent-ids-limit", 500));
        while (recentOrder.size() > limit) {
            String removed = recentOrder.pollFirst();
            if (removed == null) {
                break;
            }
            recentRecords.remove(removed);
        }
    }

    private void enqueueRecord(ChatMessageAuditRecord record) {
        writeQueue.addLast(record);
        queueSize.incrementAndGet();

        int queueLimit = Math.max(1000, plugin.configuration().getInt("message-inspector.writer.queue-limit", 10000));
        while (queueSize.get() > queueLimit) {
            ChatMessageAuditRecord removed = writeQueue.pollFirst();
            if (removed == null) {
                break;
            }
            queueSize.decrementAndGet();
        }
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (Exception exception) {
            logInsertError("Message audit flush failed: " + exception.getMessage());
        }
    }

    private void healthCheckSafely() {
        try {
            runHealthCheck();
        } catch (Exception exception) {
            logConnectionError("Message audit health-check failed: " + exception.getMessage());
        }
    }

    private void runHealthCheck() {
        if (!isEnabled()) {
            return;
        }

        if (!ensureDataSource()) {
            return;
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (Exception exception) {
            closeDataSource();
            logConnectionError("Message audit database ping failed: " + exception.getMessage());
        }
    }

    private void flushNow() {
        if (!isEnabled()) {
            clearQueue();
            return;
        }

        int batchSize = Math.max(10, plugin.configuration().getInt("message-inspector.writer.batch-size", 100));
        List<ChatMessageAuditRecord> batch = pollBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        writeBackup(batch);

        if (!ensureDataSource()) {
            requeueFront(batch);
            return;
        }

        try {
            insertBatch(batch);
        } catch (Exception exception) {
            closeDataSource();
            requeueFront(batch);
            logInsertError("Message audit insert failed: " + exception.getMessage());
        }
    }

    private void clearQueue() {
        writeQueue.clear();
        queueSize.set(0);
    }

    private List<ChatMessageAuditRecord> pollBatch(int size) {
        List<ChatMessageAuditRecord> batch = new ArrayList<>(size);
        while (batch.size() < size) {
            ChatMessageAuditRecord record = writeQueue.pollFirst();
            if (record == null) {
                break;
            }
            queueSize.decrementAndGet();
            batch.add(record);
        }
        return batch;
    }

    private void requeueFront(List<ChatMessageAuditRecord> batch) {
        for (int index = batch.size() - 1; index >= 0; index--) {
            writeQueue.addFirst(batch.get(index));
            queueSize.incrementAndGet();
        }
        int queueLimit = Math.max(1000, plugin.configuration().getInt("message-inspector.writer.queue-limit", 10000));
        while (queueSize.get() > queueLimit) {
            ChatMessageAuditRecord removed = writeQueue.pollFirst();
            if (removed == null) {
                break;
            }
            queueSize.decrementAndGet();
        }
    }

    private boolean ensureDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            return true;
        }

        long retryMillis = Math.max(5L, plugin.configuration().getLong("message-inspector.database.retry-connect-seconds", 15L)) * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastConnectAttemptMillis < retryMillis) {
            return false;
        }

        synchronized (datasourceLock) {
            if (dataSource != null && !dataSource.isClosed()) {
                return true;
            }
            now = System.currentTimeMillis();
            if (now - lastConnectAttemptMillis < retryMillis) {
                return false;
            }
            lastConnectAttemptMillis = now;

            try {
                HikariDataSource newDataSource = createDataSource();
                createTableIfNeeded(newDataSource);
                dataSource = newDataSource;
                plugin.getLogger().info("[MessageInspector] Database connection is ready (" + activeProfileName + ")");
                return true;
            } catch (Exception exception) {
                logConnectionError("Message inspector connection failed: " + exception.getMessage());
                return false;
            }
        }
    }

    private HikariDataSource createDataSource() {
        String mode = plugin.configuration().getString("message-inspector.database.mode", "LOCAL");
        String normalizedMode = mode == null ? "LOCAL" : mode.trim().toUpperCase(Locale.ROOT);
        if (!normalizedMode.equals("REMOTE")) {
            normalizedMode = "LOCAL";
        }
        activeProfileName = normalizedMode;

        String basePath = "message-inspector.database." + normalizedMode.toLowerCase(Locale.ROOT);
        String host = plugin.configuration().getString(basePath + ".host", "127.0.0.1");
        int port = plugin.configuration().getInt(basePath + ".port", 3306);
        String database = plugin.configuration().getString(basePath + ".database", "cloverchat");
        String username = plugin.configuration().getString(basePath + ".username", "root");
        String password = plugin.configuration().getString(basePath + ".password", "");
        boolean useSsl = plugin.configuration().getBoolean(basePath + ".use-ssl", false);
        boolean allowPublicKeyRetrieval = plugin.configuration().getBoolean(basePath + ".allow-public-key-retrieval", true);
        String timezone = plugin.configuration().getString(basePath + ".server-timezone", "UTC");

        String jdbcUrl = buildJdbcUrl(host, port, database, useSsl, allowPublicKeyRetrieval, timezone);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName(plugin.configuration().getString("message-inspector.database.hikari.pool-name", "CloverChat-MessageInspector"));
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(Math.max(2, plugin.configuration().getInt("message-inspector.database.hikari.maximum-pool-size", 8)));
        hikariConfig.setMinimumIdle(Math.max(1, plugin.configuration().getInt("message-inspector.database.hikari.minimum-idle", 2)));
        hikariConfig.setConnectionTimeout(Math.max(3000L, plugin.configuration().getLong("message-inspector.database.hikari.connection-timeout-ms", 10000L)));
        hikariConfig.setValidationTimeout(Math.max(1000L, plugin.configuration().getLong("message-inspector.database.hikari.validation-timeout-ms", 5000L)));
        hikariConfig.setIdleTimeout(Math.max(10000L, plugin.configuration().getLong("message-inspector.database.hikari.idle-timeout-ms", 600000L)));
        hikariConfig.setMaxLifetime(Math.max(60000L, plugin.configuration().getLong("message-inspector.database.hikari.max-lifetime-ms", 1800000L)));
        hikariConfig.setKeepaliveTime(Math.max(30000L, plugin.configuration().getLong("message-inspector.database.hikari.keepalive-time-ms", 300000L)));
        hikariConfig.setInitializationFailTimeout(-1L);
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
        hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
        hikariConfig.addDataSourceProperty("tcpKeepAlive", "true");

        return new HikariDataSource(hikariConfig);
    }

    private String buildJdbcUrl(
            String host,
            int port,
            String database,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            String timezone
    ) {
        String resolvedHost = host == null || host.isBlank() ? "127.0.0.1" : host;
        int resolvedPort = port <= 0 ? 3306 : port;
        String resolvedDatabase = database == null || database.isBlank() ? "cloverchat" : database;
        String resolvedTimezone = timezone == null || timezone.isBlank() ? "UTC" : timezone;
        return "jdbc:mysql://" + resolvedHost + ":" + resolvedPort + "/" + resolvedDatabase
                + "?useUnicode=true"
                + "&characterEncoding=utf8"
                + "&useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                + "&serverTimezone=" + resolvedTimezone;
    }

    private void createTableIfNeeded(HikariDataSource source) throws Exception {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT,"
                + "message_id VARCHAR(80) NOT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "message_time VARCHAR(40) NOT NULL,"
                + "server_id VARCHAR(80) NOT NULL,"
                + "player_uuid VARCHAR(40) NOT NULL,"
                + "player_name VARCHAR(64) NOT NULL,"
                + "player_display_name VARCHAR(255) NOT NULL,"
                + "player_group_name VARCHAR(120) NOT NULL,"
                + "player_prefix TEXT,"
                + "player_reputation VARCHAR(120) NOT NULL,"
                + "chat_mode VARCHAR(20) NOT NULL,"
                + "chat_type_name VARCHAR(120) NOT NULL,"
                + "group_channel VARCHAR(120) NOT NULL,"
                + "world_name VARCHAR(120) NOT NULL,"
                + "pos_x DOUBLE NOT NULL,"
                + "pos_y DOUBLE NOT NULL,"
                + "pos_z DOUBLE NOT NULL,"
                + "raw_input TEXT NOT NULL,"
                + "chat_message TEXT NOT NULL,"
                + "final_message_json LONGTEXT NOT NULL,"
                + "view_permission VARCHAR(255) NOT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uq_message_id (message_id),"
                + "KEY idx_player_name (player_name),"
                + "KEY idx_created_at (created_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void insertBatch(List<ChatMessageAuditRecord> batch) throws Exception {
        String sql = "INSERT INTO " + TABLE_NAME + " ("
                + "message_id, created_at, message_time, server_id, player_uuid, player_name, player_display_name, "
                + "player_group_name, player_prefix, player_reputation, chat_mode, chat_type_name, group_channel, world_name, "
                + "pos_x, pos_y, pos_z, raw_input, chat_message, final_message_json, view_permission"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE "
                + "created_at = VALUES(created_at), "
                + "message_time = VALUES(message_time), "
                + "server_id = VALUES(server_id), "
                + "player_uuid = VALUES(player_uuid), "
                + "player_name = VALUES(player_name), "
                + "player_display_name = VALUES(player_display_name), "
                + "player_group_name = VALUES(player_group_name), "
                + "player_prefix = VALUES(player_prefix), "
                + "player_reputation = VALUES(player_reputation), "
                + "chat_mode = VALUES(chat_mode), "
                + "chat_type_name = VALUES(chat_type_name), "
                + "group_channel = VALUES(group_channel), "
                + "world_name = VALUES(world_name), "
                + "pos_x = VALUES(pos_x), "
                + "pos_y = VALUES(pos_y), "
                + "pos_z = VALUES(pos_z), "
                + "raw_input = VALUES(raw_input), "
                + "chat_message = VALUES(chat_message), "
                + "final_message_json = VALUES(final_message_json), "
                + "view_permission = VALUES(view_permission)";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ChatMessageAuditRecord record : batch) {
                statement.setString(1, record.messageId());
                statement.setLong(2, record.createdAtMillis());
                statement.setString(3, record.messageTime());
                statement.setString(4, record.serverId());
                statement.setString(5, record.playerUuid());
                statement.setString(6, record.playerName());
                statement.setString(7, record.playerDisplayName());
                statement.setString(8, record.playerGroup());
                statement.setString(9, record.playerPrefix());
                statement.setString(10, record.playerReputation());
                statement.setString(11, record.chatMode());
                statement.setString(12, record.chatType());
                statement.setString(13, record.groupChannel());
                statement.setString(14, record.worldName());
                statement.setDouble(15, record.x());
                statement.setDouble(16, record.y());
                statement.setDouble(17, record.z());
                statement.setString(18, record.rawInput());
                statement.setString(19, record.chatMessage());
                statement.setString(20, record.finalMessageJson());
                statement.setString(21, record.viewPermission());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writeBackup(List<ChatMessageAuditRecord> batch) {
        if (!plugin.configuration().getBoolean("message-inspector.backup.enabled", true)) {
            return;
        }

        String folder = plugin.configuration().getString("message-inspector.backup.folder", "backups/message-inspector");
        File backupFolder = new File(plugin.getDataFolder(), folder == null ? "backups/message-inspector" : folder);
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            logBackupError("Message audit backup folder is not available: " + backupFolder.getAbsolutePath());
            return;
        }

        String fileName = "messages-" + LocalDate.now() + ".jsonl";
        File backupFile = new File(backupFolder, fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(
                backupFile.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            for (ChatMessageAuditRecord record : batch) {
                writer.write(toJson(record));
                writer.newLine();
            }
        } catch (IOException exception) {
            logBackupError("Message audit backup write failed: " + exception.getMessage());
        }
    }

    private String toJson(ChatMessageAuditRecord record) {
        return "{"
                + "\"message_id\":\"" + json(record.messageId()) + "\","
                + "\"created_at\":" + record.createdAtMillis() + ","
                + "\"message_time\":\"" + json(record.messageTime()) + "\","
                + "\"server_id\":\"" + json(record.serverId()) + "\","
                + "\"player_uuid\":\"" + json(record.playerUuid()) + "\","
                + "\"player_name\":\"" + json(record.playerName()) + "\","
                + "\"player_display_name\":\"" + json(record.playerDisplayName()) + "\","
                + "\"player_group\":\"" + json(record.playerGroup()) + "\","
                + "\"player_prefix\":\"" + json(record.playerPrefix()) + "\","
                + "\"player_reputation\":\"" + json(record.playerReputation()) + "\","
                + "\"chat_mode\":\"" + json(record.chatMode()) + "\","
                + "\"chat_type\":\"" + json(record.chatType()) + "\","
                + "\"group_channel\":\"" + json(record.groupChannel()) + "\","
                + "\"world\":\"" + json(record.worldName()) + "\","
                + "\"x\":" + record.x() + ","
                + "\"y\":" + record.y() + ","
                + "\"z\":" + record.z() + ","
                + "\"message_raw\":\"" + json(record.rawInput()) + "\","
                + "\"message_final\":\"" + json(record.chatMessage()) + "\","
                + "\"view_permission\":\"" + json(record.viewPermission()) + "\""
                + "}";
    }

    private String json(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (symbol == '\\') {
                builder.append("\\\\");
                continue;
            }
            if (symbol == '"') {
                builder.append("\\\"");
                continue;
            }
            if (symbol == '\r') {
                builder.append("\\r");
                continue;
            }
            if (symbol == '\n') {
                builder.append("\\n");
                continue;
            }
            if (symbol == '\t') {
                builder.append("\\t");
                continue;
            }
            builder.append(symbol);
        }
        return builder.toString();
    }

    private void closeDataSource() {
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null) {
            try {
                source.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void logConnectionError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastConnectionErrorLogMillis < 15000L) {
            return;
        }
        lastConnectionErrorLogMillis = now;
        plugin.getLogger().warning("[MessageInspector] " + message);
    }

    private void logInsertError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastInsertErrorLogMillis < 15000L) {
            return;
        }
        lastInsertErrorLogMillis = now;
        plugin.getLogger().warning("[MessageInspector] " + message);
    }

    private void logBackupError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastBackupErrorLogMillis < 15000L) {
            return;
        }
        lastBackupErrorLogMillis = now;
        plugin.getLogger().warning("[MessageInspector] " + message);
    }
}
