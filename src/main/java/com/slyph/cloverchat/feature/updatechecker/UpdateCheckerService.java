package com.slyph.cloverchat.feature.updatechecker;

import com.slyph.cloverchat.CloverChatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateCheckerService {

    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_PATTERN = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final CloverChatPlugin plugin;
    private final HttpClient httpClient;
    private BukkitTask periodicTask;

    public UpdateCheckerService(CloverChatPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void start() {
        stop();

        if (!plugin.configuration().getBoolean("update-checker.enabled", true)) {
            return;
        }

        if (plugin.configuration().getBoolean("update-checker.check-on-startup", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkNow);
        }

        long intervalHours = plugin.configuration().getLong("update-checker.check-interval-hours", 6L);
        if (intervalHours <= 0L) {
            return;
        }

        long intervalTicks = intervalHours * 72000L;
        periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkNow, intervalTicks, intervalTicks);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    private void checkNow() {
        String repository = normalizeRepository(plugin.configuration().getString("update-checker.repository", "slyphmp4/CloverChat"));
        if (repository == null) {
            plugin.getLogger().warning("[UpdateChecker] Неверный формат update-checker.repository");
            return;
        }

        String currentVersion = plugin.getDescription().getVersion();
        int timeoutSeconds = Math.max(3, plugin.configuration().getInt("update-checker.request-timeout-seconds", 10));
        String endpoint = "https://api.github.com/repos/" + repository + "/releases/latest";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "CloverChat/" + currentVersion)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception exception) {
            plugin.getLogger().warning("[UpdateChecker] Не удалось проверить обновления: " + exception.getMessage());
            return;
        }

        if (response.statusCode() != 200) {
            if (response.statusCode() == 404) {
                plugin.getLogger().warning("[UpdateChecker] Релизы не найдены для " + repository);
                return;
            }
            plugin.getLogger().warning("[UpdateChecker] Ошибка проверки обновлений. HTTP " + response.statusCode());
            return;
        }

        String body = response.body();
        String latestVersion = extractJsonString(body, TAG_PATTERN);
        if (latestVersion == null || latestVersion.isBlank()) {
            plugin.getLogger().warning("[UpdateChecker] Не удалось определить версию последнего релиза");
            return;
        }

        String releaseUrl = extractJsonString(body, URL_PATTERN);
        if (releaseUrl == null || releaseUrl.isBlank()) {
            releaseUrl = "https://github.com/" + repository + "/releases/latest";
        }

        if (isUpdateAvailable(currentVersion, latestVersion)) {
            plugin.getLogger().warning("[UpdateChecker] Доступно обновление CloverChat");
            plugin.getLogger().warning("[UpdateChecker] Текущая версия: " + currentVersion + ", новая версия: " + latestVersion);
            plugin.getLogger().warning("[UpdateChecker] Обновите плагин: " + releaseUrl);
            return;
        }

        if (plugin.configuration().getBoolean("update-checker.log-no-update", false)) {
            plugin.getLogger().info("[UpdateChecker] Установлена актуальная версия: " + currentVersion);
        }
    }

    private String normalizeRepository(String input) {
        if (input == null) {
            return null;
        }

        String repository = input.trim().replace('\\', '/');
        if (repository.isBlank()) {
            return null;
        }

        int githubIndex = repository.toLowerCase(Locale.ROOT).indexOf("github.com/");
        if (githubIndex >= 0) {
            repository = repository.substring(githubIndex + "github.com/".length());
        }

        while (repository.startsWith("/")) {
            repository = repository.substring(1);
        }

        if (repository.endsWith(".git")) {
            repository = repository.substring(0, repository.length() - 4);
        }

        String[] parts = repository.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }

        return parts[0] + "/" + parts[1];
    }

    private String extractJsonString(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\/", "/");
    }

    private boolean isUpdateAvailable(String currentVersion, String latestVersion) {
        String normalizedCurrent = normalizeVersion(currentVersion);
        String normalizedLatest = normalizeVersion(latestVersion);

        int compareResult = compareVersionNumbers(normalizedCurrent, normalizedLatest);
        if (compareResult < 0) {
            return true;
        }
        if (compareResult > 0) {
            return false;
        }

        boolean currentPre = isPreRelease(normalizedCurrent);
        boolean latestPre = isPreRelease(normalizedLatest);
        return currentPre && !latestPre;
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private int compareVersionNumbers(String currentVersion, String latestVersion) {
        List<Integer> currentNumbers = extractNumbers(currentVersion);
        List<Integer> latestNumbers = extractNumbers(latestVersion);

        int max = Math.max(currentNumbers.size(), latestNumbers.size());
        for (int index = 0; index < max; index++) {
            int current = index < currentNumbers.size() ? currentNumbers.get(index) : 0;
            int latest = index < latestNumbers.size() ? latestNumbers.get(index) : 0;
            if (current != latest) {
                return Integer.compare(current, latest);
            }
        }

        return 0;
    }

    private List<Integer> extractNumbers(String version) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(version == null ? "" : version);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                return List.of(0);
            }
        }
        if (numbers.isEmpty()) {
            numbers.add(0);
        }
        return numbers;
    }

    private boolean isPreRelease(String version) {
        String lower = version == null ? "" : version.toLowerCase(Locale.ROOT);
        return lower.contains("snapshot")
                || lower.contains("alpha")
                || lower.contains("beta")
                || lower.contains("pre")
                || lower.contains("rc");
    }
}
