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
import java.util.List;

public final class CloverChatPlugin extends JavaPlugin {

    private boolean placeholderApiHooked;
    private HeadMessageService headMessageService;
    private UpdateCheckerService updateCheckerService;
    private FileConfiguration messagesConfiguration;
    private FileConfiguration hoversConfiguration;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAdditionalConfigurations();
        placeholderApiHooked = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        headMessageService = new HeadMessageService(this);
        updateCheckerService = new UpdateCheckerService(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
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
        File messagesFile = ensureResourceFile("messages.yml");
        File hoversFile = ensureResourceFile("hovers.yml");
        messagesConfiguration = YamlConfiguration.loadConfiguration(messagesFile);
        hoversConfiguration = YamlConfiguration.loadConfiguration(hoversFile);
    }

    private File ensureResourceFile(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.exists()) {
            saveResource(fileName, false);
        }
        return file;
    }
}
