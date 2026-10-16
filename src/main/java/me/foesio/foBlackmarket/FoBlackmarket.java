package me.foesio.foBlackmarket;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.dialog.NativeDialogConfigDefaults;
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
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.gui.PublicMarketMigration;
import me.foesio.foBlackmarket.market.MarketRepository;
import me.foesio.foBlackmarket.market.MarketService;
import me.foesio.foBlackmarket.performance.ActionCooldowns;
import me.foesio.foBlackmarket.placeholder.PlaceholderApiBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private GuiManager guiManager;
    private MarketService marketService;
    private ActionCooldowns actionCooldowns;
    private UpdateNoticeService updateNotices;
    private FoSoundService sounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private FoAdminSounds adminSounds;
    private boolean publicGuiMigrationReady;

    @Override
    public void onEnable() {
        PublicMarketMigration.SourceSnapshot publicGuiSource = PublicMarketMigration.snapshot(this);
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.reload();
        createCoreDialogFoundation();

        this.marketRepository = new MarketRepository(this);
        if (publicGuiSource.freshInstall()) {
            this.marketRepository.ensureExists();
        }
        this.publicGuiMigrationReady = new PublicMarketMigration(this).migrate(publicGuiSource);
        loadCoreSounds();
        this.messages = publicGuiMigrationReady
            ? FoMessageService.loadPreservingRemovals(this, messageMigrations())
            : FoMessageService.loadPreservingRemovals(this, FoMessageMigrations.none());
        if (!publicGuiMigrationReady) {
            getLogger().severe("Public market GUI upgrade is pending; player menus will remain unavailable.");
        } else {
            messages.reload();
            sounds.reloadChecked();
            removeLegacyConfigMessageSections();
        }

        this.vaultEconomy = core.createVaultEconomy();
        this.actionCooldowns = new ActionCooldowns();
        this.guiManager = new GuiManager(this);
        this.marketService = new MarketService(this, configManager, marketRepository, vaultEconomy, guiManager);
        boolean publicGuiLoaded = false;
        if (publicGuiMigrationReady) {
            try {
                preflightMessages();
                publicGuiLoaded = guiManager.reloadPublicMenus().successful();
            } catch (IllegalStateException exception) {
                getLogger().severe("Public market menus are unavailable: " + exception.getMessage());
            }
        }
        this.updateNotices = core.createUpdateNotices(messages, MODRINTH_PROJECT_ID, adminSounds).start();

        getServer().getPluginManager().registerEvents(new GuiListener(this, guiManager), this);
        getServer().getPluginManager().registerEvents(actionCooldowns, this);

        registerCommands();

        if (publicGuiLoaded) {
            marketService.start();
        }

        registerBlackmarketPlaceholders();
    }

    @Override
    public void onDisable() {
        unregisterBlackmarketPlaceholders();
        if (marketService != null) {
            try {
                marketService.shutdown();
            } catch (IllegalStateException exception) {
                getLogger().severe("Could not persist market state on shutdown: " + exception.getMessage());
            }
        }
        if (guiManager != null) {
            guiManager.close();
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
        createCoreDialogFoundation();
        loadCoreSounds();
    }

    private void createCoreDialogFoundation() {
        NativeDialogConfigDefaults.addDefaults(getConfig());
        if (publicGuiMigrationReady) {
            saveConfig();
        }
        if (core != null) {
            core.close();
        }
        this.core = FoPluginCore.create(this);
        core.metrics(BSTATS_PLUGIN_ID);
        core.warnIfNativeDialogsUnavailable();
    }

    private void loadCoreSounds() {
        this.sounds = FoSoundService.loadPreservingRemovals(this);
        this.editorSounds = FoEditorSounds.create(sounds);
        this.guiSounds = FoGuiSounds.create(sounds);
        this.adminSounds = FoAdminSounds.create(sounds);
    }

    public FoReloadResult reloadFiles() {
        return FoReloadRegistry.create()
            .add("public GUI preflight", this::preflightPublicMenus)
            .add("message preflight", this::preflightMessages)
            .add("sound preflight", () -> {
                if (!sounds.validateInstalled()) {
                    throw new IllegalStateException("Invalid sounds.yml; previous sounds remain active");
                }
            })
            .add("config", configManager::reload)
            .add("sounds", () -> {
                if (!sounds.reloadChecked()) {
                    throw new IllegalStateException("Invalid sounds.yml; previous sounds remain active");
                }
            })
            .addMessages(messages)
            .add("dialog foundation", this::reloadCoreDialogFoundation)
            .add("admin integrations", this::refreshAdminIntegrations)
            .add("guis", this::reloadPublicGuiDefinitions)
            .add("market", marketService::activatePublicGuiReload)
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

    private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
            .add(messagesConfig -> {
                boolean changed = false;
                changed |= moveMessageSection(messagesConfig, "colors", "tokens");
                changed |= moveMessageValue(messagesConfig, "prefix", "tokens.prefix");
                return changed;
            })
            .removeIfMemorySectionString("general")
            .removeIfMemorySectionString("admin")
            .removeIfMemorySectionString("native-dialogs")
            .removeIfMemorySectionString("editor")
            .build();
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
        changed |= removeConfigPath(config, "settings.time-format");
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

    private void reloadPublicGuiDefinitions() {
        if (!guiManager.reloadPublicMenus().successful()) {
            throw new IllegalStateException("Public GUI reload rejected; previous working menus remain active");
        }
    }

    private void preflightPublicMenus() {
        if (!publicGuiMigrationReady) {
            publicGuiMigrationReady = new PublicMarketMigration(this).migrate(PublicMarketMigration.snapshot(this));
        }
        if (!publicGuiMigrationReady) {
            throw new IllegalStateException("Public GUI upgrade is still pending");
        }
        if (!guiManager.publicMenus().validateInstalled().successful()) {
            throw new IllegalStateException("Public GUI validation rejected; previous working menus remain active");
        }
    }

    private void preflightMessages() {
        File file = new File(getDataFolder(), MESSAGES_RESOURCE);
        if (!file.isFile()) {
            throw new IllegalStateException("messages.yml is missing");
        }
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("messages.yml cannot be parsed: " + exception.getMessage(), exception);
        }
        validatePublicMessageTypes(candidate);
    }

    static void validatePublicMessageTypes(YamlConfiguration candidate) {
        for (String root : new String[] {"tokens", "gui", "messages"}) {
            ConfigurationSection section = candidate.getConfigurationSection(root);
            if (candidate.isSet(root) && section == null) {
                throw new IllegalStateException("messages.yml:" + root + " must be a section");
            }
            if (section == null) {
                continue;
            }
            for (String key : section.getKeys(true)) {
                if (!section.isConfigurationSection(key) && !(section.get(key) instanceof String)) {
                    throw new IllegalStateException("messages.yml:" + root + "." + key + " must be text");
                }
            }
        }
        String languageTag = candidate.getString("gui.number-locale");
        if (languageTag != null && !languageTag.isBlank()) {
            try {
                new java.util.Locale.Builder().setLanguageTag(languageTag);
            } catch (java.util.IllformedLocaleException exception) {
                throw new IllegalStateException("messages.yml:gui.number-locale is not a valid language tag", exception);
            }
        }
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
