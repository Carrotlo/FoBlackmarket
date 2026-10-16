package me.foesio.foBlackmarket.config;

import java.time.Duration;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigManager {

    private final FoBlackmarket plugin;
    private FileConfiguration config;
    private long cachedRestockIntervalMinutes = 60L;
    private boolean cachedAnnounceRestocks = true;
    private long cachedPurchaseCooldownMillis = 100L;
    private long cachedOpenGuiCooldownMillis = 100L;
    private boolean cachedCommandOpensGui = true;
    private boolean cachedSendCooldownFeedback;
    private boolean cachedSendFailedPurchaseFeedback = true;
    private long cachedFailedPurchaseFeedbackCooldownMillis = 750L;

    public ConfigManager(FoBlackmarket plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        rebuildCaches();
    }

    public void save() {
        plugin.saveConfig();
        reload();
    }

    public long getRestockIntervalMinutes() {
        return cachedRestockIntervalMinutes;
    }

    public void setRestockIntervalMinutes(long minutes) {
        config.set("settings.restock-interval-minutes", Math.max(1L, minutes));
        save();
    }

    public boolean isAnnounceRestocks() {
        return cachedAnnounceRestocks;
    }

    public void setAnnounceRestocks(boolean announceRestocks) {
        config.set("settings.announce-restocks", announceRestocks);
        save();
    }

    public long getPurchaseCooldownMillis() {
        return cachedPurchaseCooldownMillis;
    }

    public long getOpenGuiCooldownMillis() {
        return cachedOpenGuiCooldownMillis;
    }

    public boolean shouldCommandOpenGui() {
        return cachedCommandOpensGui;
    }

    public boolean shouldSendCooldownFeedback() {
        return cachedSendCooldownFeedback;
    }

    public boolean shouldSendFailedPurchaseFeedback() {
        return cachedSendFailedPurchaseFeedback;
    }

    public long getFailedPurchaseFeedbackCooldownMillis() {
        return cachedFailedPurchaseFeedbackCooldownMillis;
    }

    public String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.getSeconds());
        if (totalSeconds == 0L) {
            return plugin.getMessages().render("gui.duration-zero", "", Map.of("value", formatCount(0)));
        }
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        List<String> parts = new ArrayList<>();
        addDurationPart(parts, "day", days);
        addDurationPart(parts, "hour", hours);
        addDurationPart(parts, "minute", minutes);
        addDurationPart(parts, "second", seconds);
        return String.join(plugin.getMessages().render("gui.duration-separator", ""), parts);
    }

    public String formatCount(long count) {
        String languageTag = plugin.getMessages().render("gui.number-locale", "");
        Locale locale = languageTag.isBlank() ? Locale.ROOT : Locale.forLanguageTag(languageTag);
        NumberFormat formatter = NumberFormat.getIntegerInstance(locale);
        formatter.setGroupingUsed(false);
        return formatter.format(count);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    private void rebuildCaches() {
        this.cachedRestockIntervalMinutes = Math.max(1L, config.getLong("settings.restock-interval-minutes", 60L));
        this.cachedAnnounceRestocks = config.getBoolean("settings.announce-restocks", true);
        this.cachedPurchaseCooldownMillis = Math.max(0L, config.getLong("settings.performance.purchase-cooldown-millis", 100L));
        this.cachedOpenGuiCooldownMillis = Math.max(0L, config.getLong("settings.performance.open-gui-cooldown-millis", 100L));
        this.cachedCommandOpensGui = config.getBoolean("settings.command-opens-gui", true);
        this.cachedSendCooldownFeedback = config.getBoolean("settings.performance.send-cooldown-feedback", false);
        this.cachedSendFailedPurchaseFeedback = config.getBoolean("settings.performance.send-failed-purchase-feedback", true);
        this.cachedFailedPurchaseFeedbackCooldownMillis = Math.max(0L, config.getLong("settings.performance.failed-purchase-feedback-cooldown-millis", 750L));
    }

    private void addDurationPart(List<String> parts, String unit, long value) {
        if (value <= 0L) {
            return;
        }
        String path = "gui.duration-" + unit + (value == 1L ? "-one" : "-other");
        String text = plugin.getMessages().render(path, "", Map.of("value", formatCount(value)));
        if (!text.isEmpty()) {
            parts.add(text);
        }
    }
}
