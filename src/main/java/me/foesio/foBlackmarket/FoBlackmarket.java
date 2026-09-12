package me.foesio.foBlackmarket;

import java.io.File;
import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.dialog.NativeDialogConfigDefaults;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.core.text.FoText;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foBlackmarket.command.BlackmarketAdminCommand;
import me.foesio.foBlackmarket.command.BlackmarketCommand;
import me.foesio.foBlackmarket.config.ConfigManager;
import me.foesio.foBlackmarket.gui.GuiListener;
import me.foesio.foBlackmarket.gui.GuiConfigManager;
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.market.MarketRepository;
import me.foesio.foBlackmarket.market.MarketService;
import me.foesio.foBlackmarket.performance.ActionCooldowns;
import me.foesio.foBlackmarket.placeholder.PlaceholderApiBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class FoBlackmarket extends JavaPlugin {

    private static final String MODRINTH_PROJECT_ID = "foblackmarket";
    private static final int BSTATS_PLUGIN_ID = 33139;
    private static final String MESSAGES_RESOURCE = "messages.yml";
    private static final String GUI_TITLE_MARKER = "&r&0";

    private FoCoreContext core;
    private FoMessageService messages;
    private ConfigManager configManager;
    private MarketRepository marketRepository;
    private VaultEconomyBridge vaultEconomy;
    private GuiConfigManager guiConfigManager;
    private GuiManager guiManager;
    private MarketService marketService;
    private ActionCooldowns actionCooldowns;
    private UpdateNoticeService updateNotices;
    private FoSoundService sounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private FoAdminSounds adminSounds;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.configManager.reload();
        this.messages = loadMessages();
        reloadCoreDialogFoundation();
        migrateSprites();

        this.guiConfigManager = new GuiConfigManager(this);
        this.guiConfigManager.ensureDefaults(configManager.getConfig());
        this.guiConfigManager.reload();
        removeLegacyConfigMessageSections();

        this.marketRepository = new MarketRepository(this);
        this.marketRepository.ensureExists();

        this.vaultEconomy = core.createVaultEconomy();
        this.actionCooldowns = new ActionCooldowns();
        this.guiManager = new GuiManager(this);
        this.marketService = new MarketService(this, configManager, marketRepository, vaultEconomy, guiManager);
        this.updateNotices = core.createUpdateNotices(messages, MODRINTH_PROJECT_ID, adminSounds).start();

        getServer().getPluginManager().registerEvents(new GuiListener(this, guiManager), this);
        getServer().getPluginManager().registerEvents(actionCooldowns, this);

        registerCommands();

        marketService.start();

        registerBlackmarketPlaceholders();
    }

    @Override
    public void onDisable() {
        unregisterBlackmarketPlaceholders();
        if (marketService != null) {
            marketService.shutdown();
        }
        if (core != null) {
            core.close();
            core = null;
        }
        updateNotices = null;
    }

    private void registerCommands() {
        PluginCommand blackmarketCommand = getCommand("blackmarket");
        if (blackmarketCommand != null) {
            blackmarketCommand.setExecutor(new BlackmarketCommand(this, configManager, marketService, guiManager));
        }

        PluginCommand adminCommand = getCommand("foblackmarketadmin");
        if (adminCommand != null) {
            BlackmarketAdminCommand.register(
                this,
                configManager,
                marketService,
                guiManager,
                updateNotices
            );
        }
    }

    public FoCoreContext getCore() {
        return core;
    }

    public FoMessageService getMessages() {
        return messages;
    }

    public FoSoundService getSounds() {
        return sounds;
    }

    public FoEditorSounds getEditorSounds() {
        return editorSounds;
    }

    public FoGuiSounds getGuiSounds() {
        return guiSounds;
    }

    public FoAdminSounds getAdminSounds() {
        return adminSounds;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MarketRepository getMarketRepository() {
        return marketRepository;
    }

    public void reloadCoreDialogFoundation() {
        NativeDialogConfigDefaults.addDefaults(getConfig());
        saveConfig();
        if (core != null) {
            core.close();
        }
        this.core = FoPluginCore.create(this);
        this.sounds = core.createSounds();
        this.editorSounds = FoEditorSounds.create(sounds);
        this.guiSounds = FoGuiSounds.create(sounds);
        this.adminSounds = FoAdminSounds.create(sounds);
        core.metrics(BSTATS_PLUGIN_ID);
        core.warnIfNativeDialogsUnavailable();
    }

    public FoReloadResult reloadFiles() {
        return FoReloadRegistry.create()
            .add("config", configManager::reload)
            .add("sounds", sounds::reload)
            .addMessages(messages)
            .add("dialog foundation", this::reloadCoreDialogFoundation)
            .add("admin integrations", this::refreshAdminIntegrations)
            .add("guis", this::reloadGuiConfigManager)
            .add("legacy message cleanup", this::removeLegacyConfigMessageSections)
            .add("market", marketService::reloadFromDisk)
            .reload();
    }

    private void refreshAdminIntegrations() {
        if (updateNotices != null) {
            HandlerList.unregisterAll(updateNotices);
        }
        updateNotices = core.createUpdateNotices(messages, MODRINTH_PROJECT_ID, adminSounds).start();
        registerCommands();
    }

    private void registerBlackmarketPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        unregisterBlackmarketPlaceholders();
        core.placeholders("foblackmarket")
            .author("Carrotio")
            .persist(true)
            .global("time_remaining", () -> configManager.formatDuration(marketService.getTimeUntilRestock()))
            .registerIfAvailable();
    }

    private void unregisterBlackmarketPlaceholders() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        PlaceholderApiBridge.unregister("foblackmarket");
    }

    public String title(String path) {
        return GuiTitles.format(messages.render("titles." + path, "&8FoBlackmarket")) + FoText.color(GUI_TITLE_MARKER);
    }

    private FoMessageService loadMessages() {
        boolean messagesFileExisted = new File(getDataFolder(), MESSAGES_RESOURCE).exists();
        return FoMessageService.load(this, messageMigrations(!messagesFileExisted));
    }

    private FoMessageMigrations messageMigrations(boolean copyLegacyConfigMessages) {
        return FoMessageMigrations.create()
            .add(messagesConfig -> {
                boolean changed = false;
                changed |= moveMessageSection(messagesConfig, "colors", "tokens");
                changed |= moveMessageValue(messagesConfig, "prefix", "tokens.prefix");
                if (copyLegacyConfigMessages) {
                    changed |= copyLegacyConfigSection("colors", messagesConfig, "tokens");
                    changed |= copyLegacyConfigValue("prefix", messagesConfig, "tokens.prefix");
                    changed |= copyLegacyConfigSection("messages", messagesConfig, "messages");
                    changed |= copyLegacyConfigSection("titles", messagesConfig, "titles");
                }
                return changed;
            })
            .removeIfMemorySectionString("general")
            .removeIfMemorySectionString("admin")
            .removeIfMemorySectionString("native-dialogs")
            .removeIfMemorySectionString("editor")
            .build();
    }

    private void migrateSprites() {
        messages.migrateToVersion(core.migrations(), 1, config -> {
            boolean changed = false;
            changed |= addSpriteIfAbsent(config, "tokens.prefix", ":emerald:", null);
            changed |= addSpriteIfAbsent(config, "messages.reload-success", ":emerald:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.reload-failed", ":redstone:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.restock-success", ":clock:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.opengui-success", ":chest:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.editor-saved", ":emerald:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.market-bought", ":emerald:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.market-purchase-failed", ":redstone:", "{prefix}");
            changed |= addSpriteIfAbsent(config, "messages.market-restocked-broadcast", ":clock:", "{prefix}");
            return true;
        });
        messages.migrateToVersion(core.migrations(), 2, config -> {
            String oldPrefix = ":emerald: {theme}FoBlackmarket &8» {muted}";
            if (!oldPrefix.equals(config.getString("tokens.prefix"))) {
                return true;
            }
            config.set("tokens.prefix", ":ender_eye: {theme}FoBlackmarket &8» {muted}");
            return true;
        });
        messages.migrateToVersion(core.migrations(), 3, config -> {
            String duplicatedDefaultPrefix = ":emerald: :ender_eye: {theme}FoBlackmarket &8» {muted}";
            if (!duplicatedDefaultPrefix.equals(config.getString("tokens.prefix"))) {
                return true;
            }
            config.set("tokens.prefix", ":ender_eye: {theme}FoBlackmarket &8» {muted}");
            return true;
        });
    }

    private boolean addSpriteIfAbsent(FileConfiguration config, String path, String token, String anchor) {
        String current = config.getString(path);
        if (current != null && DialogIcons.containsToken(current)) {
            return false;
        }
        return FoMessageService.addMissingToken(config, path, token, anchor);
    }

    private boolean moveMessageValue(FileConfiguration messagesConfig, String sourcePath, String targetPath) {
        if (!messagesConfig.contains(sourcePath)) {
            return false;
        }
        messagesConfig.set(targetPath, messagesConfig.get(sourcePath));
        messagesConfig.set(sourcePath, null);
        return true;
    }

    private boolean moveMessageSection(FileConfiguration messagesConfig, String sourcePath, String targetPath) {
        ConfigurationSection section = messagesConfig.getConfigurationSection(sourcePath);
        if (section == null) {
            return false;
        }
        copySection(section, messagesConfig, targetPath);
        messagesConfig.set(sourcePath, null);
        return true;
    }

    private boolean copyLegacyConfigValue(String sourcePath, FileConfiguration messagesConfig, String targetPath) {
        FileConfiguration source = getConfig();
        if (source == null || !source.contains(sourcePath)) {
            return false;
        }
        messagesConfig.set(targetPath, source.get(sourcePath));
        return true;
    }

    private boolean copyLegacyConfigSection(String sourcePath, FileConfiguration messagesConfig, String targetPath) {
        FileConfiguration source = getConfig();
        if (source == null) {
            return false;
        }
        ConfigurationSection section = source.getConfigurationSection(sourcePath);
        if (section == null) {
            return false;
        }
        return copySection(section, messagesConfig, targetPath);
    }

    private boolean copySection(ConfigurationSection source, FileConfiguration target, String targetPath) {
        boolean changed = false;
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                continue;
            }
            target.set(targetPath + "." + key, source.get(key));
            changed = true;
        }
        return changed;
    }

    private void removeLegacyConfigMessageSections() {
        FileConfiguration config = getConfig();
        if (config == null) {
            return;
        }
        boolean changed = false;
        changed |= removeConfigPath(config, "colors");
        changed |= removeConfigPath(config, "prefix");
        changed |= removeConfigPath(config, "messages");
        changed |= removeConfigPath(config, "titles");
        if (changed) {
            saveConfig();
        }
    }

    private boolean removeConfigPath(FileConfiguration config, String path) {
        if (!config.contains(path)) {
            return false;
        }
        config.set(path, null);
        return true;
    }

    public GuiConfigManager getGuiConfigManager() {
        return guiConfigManager;
    }

    public void reloadGuiConfigManager() {
        guiConfigManager.ensureDefaults(configManager.getConfig());
        guiConfigManager.reload();
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public MarketService getMarketService() {
        return marketService;
    }

    public ActionCooldowns getActionCooldowns() {
        return actionCooldowns;
    }

    public UpdateNoticeService getUpdateNotices() {
        return updateNotices;
    }
}
