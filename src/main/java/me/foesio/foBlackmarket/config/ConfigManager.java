package me.foesio.foBlackmarket.config;

import java.time.Duration;
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
    private String cachedDayLabel = "d";
    private String cachedHourLabel = "h";
    private String cachedMinuteLabel = "m";
    private String cachedSecondLabel = "s";

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
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder builder = new StringBuilder();
        appendTime(builder, days, cachedDayLabel);
        appendTime(builder, hours, cachedHourLabel);
        appendTime(builder, minutes, cachedMinuteLabel);
        if (builder.isEmpty() || seconds > 0L) {
            appendTime(builder, seconds, cachedSecondLabel);
        }
        return builder.toString().trim();
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
        this.cachedDayLabel = config.getString("settings.time-format.day", "d");
        this.cachedHourLabel = config.getString("settings.time-format.hour", "h");
        this.cachedMinuteLabel = config.getString("settings.time-format.minute", "m");
        this.cachedSecondLabel = config.getString("settings.time-format.second", "s");
    }

    private void appendTime(StringBuilder builder, long value, String label) {
        if (value <= 0L) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value).append(label);
    }
}
