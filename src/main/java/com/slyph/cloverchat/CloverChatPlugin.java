package com.slyph.cloverchat;

import com.slyph.cloverchat.command.PrivateMessageCommand;
import com.slyph.cloverchat.command.ReloadCommand;
import com.slyph.cloverchat.command.completer.PrivateMessageTabCompleter;
import com.slyph.cloverchat.command.completer.ReloadTabCompleter;
import com.slyph.cloverchat.feature.headmessage.HeadMessageService;
import com.slyph.cloverchat.feature.updatechecker.UpdateCheckerService;
import com.slyph.cloverchat.listener.ChatListener;
import com.slyph.cloverchat.listener.CommandCooldownListener;
import com.slyph.cloverchat.listener.JoinQuitListener;
import com.slyph.cloverchat.listener.ModernChatBridge;
import com.slyph.cloverchat.listener.QuickPrivateMessageListener;
import com.slyph.cloverchat.util.ColorUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CloverChatPlugin extends JavaPlugin {

    private static final int LOG_BOX_INNER_WIDTH = 58;
    private static final String LOG_TOP = "╔" + "═".repeat(LOG_BOX_INNER_WIDTH + 2) + "╗";
    private static final String LOG_SEPARATOR = "╠" + "═".repeat(LOG_BOX_INNER_WIDTH + 2) + "╣";
    private static final String LOG_BOTTOM = "╚" + "═".repeat(LOG_BOX_INNER_WIDTH + 2) + "╝";
    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList("ru", "ua", "en", "de");
    private static final String DEFAULT_LANGUAGE = "ru";

    private boolean placeholderApiHooked;
    private HeadMessageService headMessageService;
    private UpdateCheckerService updateCheckerService;
    private FileConfiguration messagesConfiguration;
    private FileConfiguration hoversConfiguration;
    private boolean modernChatBridgeEnabled;
    private String activeLanguage = DEFAULT_LANGUAGE;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAdditionalConfigurations();
        placeholderApiHooked = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        headMessageService = new HeadMessageService(this);
        updateCheckerService = new UpdateCheckerService(this);

        ChatListener chatListener = new ChatListener(this);
        modernChatBridgeEnabled = new ModernChatBridge(this, chatListener).register();
        if (!modernChatBridgeEnabled) {
            getServer().getPluginManager().registerEvents(chatListener, this);
        }
        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandCooldownListener(this), this);
        getServer().getPluginManager().registerEvents(new QuickPrivateMessageListener(this), this);

        PrivateMessageCommand privateMessageCommand = new PrivateMessageCommand(this);
        PluginCommand mCommand = getCommand("m");
        if (mCommand != null) {
            mCommand.setExecutor(privateMessageCommand);
            mCommand.setTabCompleter(new PrivateMessageTabCompleter());
        }

        ReloadCommand reloadCommand = new ReloadCommand(this);
        PluginCommand reloadPluginCommand = getCommand("cloverchatreload");
        if (reloadPluginCommand != null) {
            reloadPluginCommand.setExecutor(reloadCommand);
            reloadPluginCommand.setTabCompleter(new ReloadTabCompleter());
        }

        updateCheckerService.start();
        logStartupBanner();
    }

    @Override
    public void onDisable() {
        if (headMessageService != null) {
            headMessageService.clearAll();
        }
        if (updateCheckerService != null) {
            updateCheckerService.stop();
        }
    }

    public FileConfiguration configuration() {
        return getConfig();
    }

    public FileConfiguration messages() {
        return messagesConfiguration;
    }

    public FileConfiguration hovers() {
        return hoversConfiguration;
    }

    public boolean isPlaceholderApiHooked() {
        return placeholderApiHooked;
    }

    public String activeLanguage() {
        return activeLanguage;
    }

    public HeadMessageService headMessageService() {
        return headMessageService;
    }

    public String applyColor(String text) {
        return ColorUtil.colorize(text, configuration().getBoolean("hex-colors", true));
    }

    public Component deserializeColored(String text) {
        return ColorUtil.toComponent(text, configuration().getBoolean("hex-colors", true));
    }

    public String applyPlaceholders(Player player, String text) {
        if (text == null) {
            return "";
        }
        if (!placeholderApiHooked || player == null) {
            return text;
        }
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    public void sendConfiguredLines(CommandSender recipient, Player placeholderContext, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            String resolved = applyPlaceholders(placeholderContext, line);
            recipient.sendMessage(deserializeColored(resolved));
        }
    }

    public void reloadPluginConfiguration() {
        reloadConfig();
        loadAdditionalConfigurations();
        if (updateCheckerService != null) {
            updateCheckerService.restart();
        }
    }

    private void loadAdditionalConfigurations() {
        migrateLegacyLanguageFiles();
        String requestedLanguage = normalizeLanguage(configuration().getString("language", DEFAULT_LANGUAGE));
        if (!SUPPORTED_LANGUAGES.contains(requestedLanguage)) {
            requestedLanguage = DEFAULT_LANGUAGE;
        }
        activeLanguage = requestedLanguage;

        ensureLanguageResources(DEFAULT_LANGUAGE);
        ensureLanguageResources(activeLanguage);

        File messagesFile = ensureLanguageResourceFile(activeLanguage, "messages.yml");
        File hoversFile = ensureLanguageResourceFile(activeLanguage, "hovers.yml");
        messagesConfiguration = YamlConfiguration.loadConfiguration(messagesFile);
        hoversConfiguration = YamlConfiguration.loadConfiguration(hoversFile);
    }

    private void ensureLanguageResources(String language) {
        ensureLanguageResourceFile(language, "messages.yml");
        ensureLanguageResourceFile(language, "hovers.yml");
    }

    private File ensureLanguageResourceFile(String language, String fileName) {
        File file = new File(new File(getDataFolder(), "langs" + File.separator + language), fileName);
        if (!file.exists()) {
            saveResource("langs/" + language + "/" + fileName, false);
        }
        return file;
    }

    private void migrateLegacyLanguageFiles() {
        File legacyMessages = new File(getDataFolder(), "messages.yml");
        File legacyHovers = new File(getDataFolder(), "hovers.yml");
        File ruDir = new File(getDataFolder(), "langs" + File.separator + DEFAULT_LANGUAGE);
        if (!ruDir.exists()) {
            ruDir.mkdirs();
        }

        File ruMessages = new File(ruDir, "messages.yml");
        File ruHovers = new File(ruDir, "hovers.yml");

        if (legacyMessages.exists() && !ruMessages.exists()) {
            legacyMessages.renameTo(ruMessages);
        }
        if (legacyHovers.exists() && !ruHovers.exists()) {
            legacyHovers.renameTo(ruHovers);
        }
    }

    private String normalizeLanguage(String input) {
        if (input == null) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        return normalized;
    }

    private void logStartupBanner() {
        String version = getDescription().getVersion();
        String placeholderApiStatus = placeholderApiHooked ? "Подключен" : "Не найден";
        String updateCheckerStatus = configuration().getBoolean("update-checker.enabled", true) ? "Включена" : "Выключена";

        getLogger().info("");
        getLogger().info(LOG_TOP);
        getLogger().info(boxLine("CloverChat"));
        getLogger().info(boxLine("Автор: slyphmp4"));
        getLogger().info(LOG_SEPARATOR);
        getLogger().info(boxLine("Версия: " + version));
        getLogger().info(boxLine("Язык: " + activeLanguage));
        getLogger().info(boxLine("Чат режим: " + (modernChatBridgeEnabled ? "AsyncChatEvent (1.19+)" : "AsyncPlayerChatEvent")));
        getLogger().info(boxLine("PlaceholderAPI: " + placeholderApiStatus));
        getLogger().info(boxLine("Проверка обновлений: " + updateCheckerStatus));
        getLogger().info(boxLine("Конфиги: config.yml | langs/" + activeLanguage + "/"));
        getLogger().info(LOG_BOTTOM);
        getLogger().info("");
    }

    private String boxLine(String text) {
        String raw = text == null ? "" : text;
        String normalized = raw.length() > LOG_BOX_INNER_WIDTH ? raw.substring(0, LOG_BOX_INNER_WIDTH) : raw;
        String padding = " ".repeat(Math.max(0, LOG_BOX_INNER_WIDTH - normalized.length()));
        return "║ " + normalized + padding + " ║";
    }
}
