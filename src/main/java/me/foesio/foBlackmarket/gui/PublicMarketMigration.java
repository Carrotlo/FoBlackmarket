package me.foesio.foBlackmarket.gui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.foesio.core.gui.publicgui.GuiDefinitionException;
import me.foesio.core.gui.publicgui.PublicGuiContract;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** One-time, versioned conversion of the installed public-interface files. */
public final class PublicMarketMigration {

    private static final int TARGET_VERSION = 6;
    private static final Pattern LIST_ELEMENT = Pattern.compile("^(.*)\\[(\\d+)]$");
    private static final Pattern OVERLAPPING_SLOT = Pattern.compile("slot (\\d+) overlaps");
    private static final String PLAYER_FILE = "guis/player-market.yml";
    private static final String COMMAND_FILE = "guis/command-market.yml";
    private static final String MESSAGES_FILE = "messages.yml";
    private static final String MARKET_FILE = "market.yml";
    private static final List<String> ITEM_FIELDS = List.of("material", "name", "lore", "amount",
        "custom-model-data", "glow", "item-flags", "skull-skin-url");
    private static final List<String> PUBLIC_SOUND_PATHS = List.of("market.restock", "market.purchase-success",
        "market.purchase-failure", "gui.open", "gui.select", "gui.page-next", "gui.page-previous");

    private final FoBlackmarket plugin;

    public PublicMarketMigration(FoBlackmarket plugin) {
        this.plugin = plugin;
    }

    /** Capture the real pre-upgrade sources before any older Core migration writes config.yml. */
    public static SourceSnapshot snapshot(FoBlackmarket plugin) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : List.of("config.yml", MARKET_FILE, MESSAGES_FILE, "sounds.yml", PLAYER_FILE, COMMAND_FILE)) {
            Path path = plugin.getDataFolder().toPath().resolve(name);
            if (Files.isRegularFile(path)) {
                try {
                    files.put(name, Files.readAllBytes(path));
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not snapshot " + path, exception);
                }
            }
        }
        int version = 0;
        if (files.containsKey("config.yml")) {
            try {
                version = parse(files.get("config.yml"), "config.yml").getInt("config-version", 0);
            } catch (IOException exception) {
                version = -1;
            }
        }
        return new SourceSnapshot(version, files, hasPriorData(plugin.getDataFolder().toPath()));
    }

    static boolean hasPriorData(Path root) {
        if (!Files.exists(root)) return false;
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect existing plugin data", exception);
        }
    }

    public record SourceSnapshot(int originalVersion, Map<String, byte[]> files, boolean hadPriorData) {
        public SourceSnapshot(int originalVersion, Map<String, byte[]> files) {
            this(originalVersion, files, !files.isEmpty());
        }

        public SourceSnapshot {
            files = Map.copyOf(files);
        }

        public boolean freshInstall() {
            return !hadPriorData;
        }

        public boolean existed(String path) {
            return files.containsKey(path);
        }

        public byte[] original(String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? null : bytes.clone();
        }
    }

    public boolean migrate(SourceSnapshot source) {
        if (source.originalVersion() >= TARGET_VERSION) {
            return true;
        }
        if (source.originalVersion() < 0) {
            plugin.getLogger().severe("Cannot parse the older config.yml; public GUI migration is pending.");
            return false;
        }
        if (source.freshInstall()) {
            return installFresh(source);
        }
        if (!source.existed(MARKET_FILE)) {
            plugin.getLogger().severe("Older market.yml is missing; public GUI migration cannot infer owner layout.");
            return false;
        }
        if (source.originalVersion() >= 4 && (!source.existed(PLAYER_FILE)
            || !source.existed(COMMAND_FILE))) {
            plugin.getLogger().severe("An older public GUI file is missing; preserving possible owner deletion.");
            return false;
        }
        if (source.originalVersion() < 3 && (source.existed(PLAYER_FILE)
            || source.existed(COMMAND_FILE))) {
            plugin.getLogger().severe("Public GUI files exist before the verified v3 schema; "
                + "migration cannot identify their bundled defaults safely.");
            return false;
        }

        Transaction transaction = null;
        try {
            transaction = prepare(source, plugin.getDataFolder().toPath());
            Transaction prepared = transaction;
            boolean advanced = plugin.getCore().migrations().runToVersion(TARGET_VERSION, () -> {
                try {
                    prepared.commit();
                    return true;
                } catch (IOException exception) {
                    plugin.getLogger().severe("Public GUI migration commit failed: " + exception.getMessage());
                    return false;
                }
            });
            if (!advanced) {
                transaction.rollback();
            }
            return plugin.getCore().migrations().version() >= TARGET_VERSION;
        } catch (IOException | RuntimeException exception) {
            if (transaction != null) {
                try {
                    transaction.rollback();
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            plugin.getLogger().severe("Public GUI migration left installed files unchanged: " + exception.getMessage());
            return false;
        }
    }

    private boolean installFresh(SourceSnapshot source) {
        Transaction transaction = null;
        try {
            Map<String, byte[]> staged = new LinkedHashMap<>();
            for (String path : List.of(PLAYER_FILE, COMMAND_FILE, MESSAGES_FILE, "sounds.yml")) {
                staged.put(path, resourceBytes(path));
            }
            repairGui(PLAYER_FILE, PublicMarketMenus.PLAYER, parse(staged.get(PLAYER_FILE), PLAYER_FILE),
                bundled(PLAYER_FILE), PublicMarketMenus.playerContract());
            repairGui(COMMAND_FILE, PublicMarketMenus.COMMAND, parse(staged.get(COMMAND_FILE), COMMAND_FILE),
                bundled(COMMAND_FILE), PublicMarketMenus.commandContract());
            transaction = new Transaction(plugin.getDataFolder().toPath(), source, staged);
            Transaction prepared = transaction;
            boolean advanced = plugin.getCore().migrations().runToVersion(TARGET_VERSION, () -> {
                try {
                    prepared.commit();
                    return true;
                } catch (IOException exception) {
                    plugin.getLogger().severe("Fresh public GUI install failed: " + exception.getMessage());
                    return false;
                }
            });
            if (!advanced) {
                transaction.rollback();
            }
            return plugin.getCore().migrations().version() >= TARGET_VERSION;
        } catch (IOException | RuntimeException exception) {
            if (transaction != null) {
                try {
                    transaction.rollback();
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            plugin.getLogger().severe("Could not install new public GUI files: " + exception.getMessage());
            return false;
        }
    }

    Transaction prepare(SourceSnapshot source, Path dataRoot) throws IOException {
        if (source.originalVersion() == 5) {
            return prepareV5(source, dataRoot);
        }
        YamlConfiguration player = bundled(PLAYER_FILE);
        YamlConfiguration command = bundled(COMMAND_FILE);
        YamlConfiguration playerDefault = bundled(PLAYER_FILE);
        YamlConfiguration commandDefault = bundled(COMMAND_FILE);
        YamlConfiguration market = parse(source.original(MARKET_FILE), MARKET_FILE);
        for (String path : List.of("rarities", "rewards")) {
            if (market.isSet(path) && !market.isConfigurationSection(path)) {
                throw new IOException("market.yml:" + path + " must be a section");
            }
            if (!market.isConfigurationSection(path)) market.createSection(path);
        }
        repairMarketPresentation(market, bundled(MARKET_FILE));
        YamlConfiguration oldMarketDefault = bundled("legacy/public-gui/market-layout-v1.yml");
        YamlConfiguration oldConfig = source.existed("config.yml")
            ? parse(source.original("config.yml"), "config.yml") : new YamlConfiguration();
        YamlConfiguration oldConfigDefault = bundled("legacy/public-gui/config-v1.yml");
        YamlConfiguration newMessages = bundled(MESSAGES_FILE);
        YamlConfiguration messages;
        if (source.existed(MESSAGES_FILE)) {
            messages = parse(source.original(MESSAGES_FILE), MESSAGES_FILE);
        } else if (source.originalVersion() < 4) {
            messages = bundled(MESSAGES_FILE);
            overlayLegacyConfigMessages(oldConfig, bundled("legacy/public-gui/config-messages-v1.yml"),
                messages);
        } else {
            throw new IOException("Older messages.yml is missing; preserving possible owner deletion");
        }
        // Add genuinely new keys before mapping old GUI text. Mapping a deliberately
        // removed legacy key may then remove its destination without later backfill.
        installNewMessageKeys(messages, newMessages, source);
        upgradeChestSprite(messages);

        if (source.existed(PLAYER_FILE)) {
            YamlConfiguration old = parse(source.original(PLAYER_FILE), PLAYER_FILE);
            if (old.isConfigurationSection("entries") && old.isConfigurationSection("filler")) {
                throw new IOException(PLAYER_FILE + " already uses an unversioned Core schema; "
                    + "its owner values cannot be classified against a released legacy layout");
            } else {
                overlayPlayer(old, oldGuiDefault(source.originalVersion(), "player-market"),
                    player, messages, newMessages);
            }
        } else {
            overlayLegacyTitle(oldConfig, oldConfigDefault, player);
        }
        if (source.existed(COMMAND_FILE)) {
            YamlConfiguration old = parse(source.original(COMMAND_FILE), COMMAND_FILE);
            if (old.isConfigurationSection("entries") && old.isConfigurationSection("filler")) {
                throw new IOException(COMMAND_FILE + " already uses an unversioned Core schema; "
                    + "its owner values cannot be classified against a released legacy layout");
            } else {
                overlayCommand(old, oldGuiDefault(source.originalVersion(), "command-market"),
                    command, messages, newMessages);
            }
        } else {
            overlayLegacyTitle(oldConfig, oldConfigDefault, command);
        }

        overlayLegacyMarket(market, oldMarketDefault, player);
        relocateNewRarityControls(command);
        removeRetiredCommandCommandsLine(command);
        messages.set("gui.command-commands-line", null);
        repairGui(PLAYER_FILE, PublicMarketMenus.PLAYER, player, playerDefault,
            PublicMarketMenus.playerContract());
        repairGui(COMMAND_FILE, PublicMarketMenus.COMMAND, command, commandDefault,
            PublicMarketMenus.commandContract());
        overlayLegacyDuration(oldConfig, bundled("legacy/public-gui/config-messages-v1.yml"),
            messages, newMessages);
        repairMessages(messages, newMessages);
        alignMarketAssignments(market, player, bundled(MARKET_FILE));

        Map<String, byte[]> staged = new LinkedHashMap<>();
        staged.put(PLAYER_FILE, utf8(player.saveToString()));
        staged.put(COMMAND_FILE, utf8(command.saveToString()));
        staged.put(MESSAGES_FILE, utf8(messages.saveToString()));
        staged.put(MARKET_FILE, utf8(market.saveToString()));
        if (source.existed("sounds.yml")) {
            YamlConfiguration sounds = parse(source.original("sounds.yml"), "sounds.yml");
            repairSounds(sounds, bundled("sounds.yml"));
            staged.put("sounds.yml", utf8(sounds.saveToString()));
        } else if (source.originalVersion() < 4) {
            staged.put("sounds.yml", utf8(bundled("sounds.yml").saveToString()));
        }
        return new Transaction(dataRoot, source, staged);
    }

    /** Upgrade the previous Core-shaped player menu without rewriting unrelated owner files. */
    private Transaction prepareV5(SourceSnapshot source, Path dataRoot) throws IOException {
        YamlConfiguration player = bundled(PLAYER_FILE);
        YamlConfiguration playerDefault = bundled(PLAYER_FILE);
        YamlConfiguration oldPlayer = parse(source.original(PLAYER_FILE), PLAYER_FILE);
        YamlConfiguration oldPlayerDefault = bundled("legacy/public-gui/player-market-v5.yml");
        overlayChangedLeaves(oldPlayer, oldPlayerDefault, player);
        removeRetiredPlayerCommandsLine(player);
        repairGui(PLAYER_FILE, PublicMarketMenus.PLAYER, player, playerDefault,
            PublicMarketMenus.playerContract());

        YamlConfiguration command = parse(source.original(COMMAND_FILE), COMMAND_FILE);
        String originalCommand = command.saveToString();
        YamlConfiguration commandDefault = bundled(COMMAND_FILE);
        installNewCommandEmptyItems(command, commandDefault);
        removeRetiredCommandCommandsLine(command);
        repairGui(COMMAND_FILE, PublicMarketMenus.COMMAND, command, commandDefault,
            PublicMarketMenus.commandContract());

        if (!source.existed(MESSAGES_FILE)) {
            throw new IOException("Older messages.yml is missing; preserving possible owner deletion");
        }
        YamlConfiguration messages = parse(source.original(MESSAGES_FILE), MESSAGES_FILE);
        messages.set("gui.player-commands-line", null); // Retired player-menu text; backup retains it.
        messages.set("gui.command-commands-line", null);
        upgradeChestSprite(messages);
        repairMessages(messages, bundled(MESSAGES_FILE));

        Map<String, byte[]> staged = new LinkedHashMap<>();
        staged.put(PLAYER_FILE, utf8(player.saveToString()));
        if (!Objects.equals(originalCommand, command.saveToString())) {
            staged.put(COMMAND_FILE, utf8(command.saveToString()));
        }
        staged.put(MESSAGES_FILE, utf8(messages.saveToString()));
        return new Transaction(dataRoot, source, staged);
    }

    /** These entry backgrounds did not exist in the released v5 browser schema. */
    private static void installNewCommandEmptyItems(YamlConfiguration command,
                                                     YamlConfiguration defaults) {
        for (String entry : List.of("rarities", "previous-rarity-page", "next-rarity-page",
            "previous-page", "next-page")) {
            String path = "entries." + entry + ".empty-item";
            if (command.isSet(path)) {
                continue; // Preserve an owner-supplied empty appearance, including omitted optional text.
            }
            ConfigurationSection item = defaults.getConfigurationSection(path);
            for (String field : item.getKeys(false)) {
                command.set(path + "." + field, item.get(field));
            }
        }
    }

    /** Copy only owner differences from the exact v5 default into the newest generated menu. */
    static void overlayChangedLeaves(YamlConfiguration old, YamlConfiguration oldDefault,
                                     YamlConfiguration target) {
        Set<String> paths = new LinkedHashSet<>();
        collectLeaves(oldDefault, "", paths);
        collectLeaves(old, "", paths);
        for (String path : paths) {
            if (!old.isSet(path)) {
                if (oldDefault.isSet(path)) {
                    target.set(path, null);
                }
            } else if (!Objects.equals(old.get(path), oldDefault.get(path))) {
                target.set(path, old.get(path));
            }
        }
    }

    private static void collectLeaves(ConfigurationSection section, String prefix, Set<String> paths) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                collectLeaves(section.getConfigurationSection(key), path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    private static void removeRetiredPlayerCommandsLine(YamlConfiguration player) {
        String path = "entries.offers.source-item.lore.lines";
        if (!player.isList(path)) return;
        List<?> oldLines = player.getList(path);
        if (oldLines == null || !oldLines.contains("{commands_line}")) return;
        List<Object> kept = new ArrayList<>(oldLines);
        kept.removeIf("{commands_line}"::equals);
        player.set(path, kept);
    }

    private static void removeRetiredCommandCommandsLine(YamlConfiguration command) {
        String path = "entries.offers.source-item.lore.lines";
        if (!command.isList(path)) return;
        List<?> oldLines = command.getList(path);
        if (oldLines == null) return;
        List<Object> kept = new ArrayList<>(oldLines);
        if (kept.removeIf(line -> line instanceof String text && text.contains("{commands_line}"))) {
            command.set(path, kept);
        }
    }

    private YamlConfiguration oldGuiDefault(int version, String menu) throws IOException {
        if (version != 3 && version != 4) {
            throw new IOException("No verified " + menu + " bundled GUI baseline for config version " + version);
        }
        return bundled("legacy/public-gui/" + menu + "-v" + version + ".yml");
    }

    private void overlayLegacyTitle(YamlConfiguration oldConfig, YamlConfiguration oldDefaults,
                                    YamlConfiguration target) {
        copy(oldConfig, oldDefaults, "titles.market", target, "title", true);
    }

    void repairMarketPresentation(YamlConfiguration market, YamlConfiguration defaults) throws IOException {
        ConfigurationSection rarities = market.getConfigurationSection("rarities");
        ConfigurationSection rewards = market.getConfigurationSection("rewards");
        Set<String> rarityIds = rarities.getKeys(false);
        for (String id : rarityIds) {
            String path = "rarities." + id;
            if (!market.isConfigurationSection(path)) {
                repairMarketValue(market, defaults, path, value -> value instanceof ConfigurationSection);
            }
            repairMarketValue(market, defaults, path + ".name", value -> value instanceof String);
            repairMarketItem(market, defaults, path + ".icon");
        }
        for (String id : rewards.getKeys(false)) {
            String path = "rewards." + id;
            if (!market.isConfigurationSection(path)) {
                repairMarketValue(market, defaults, path, value -> value instanceof ConfigurationSection);
            }
            repairMarketValue(market, defaults, path + ".name", value -> value instanceof String);
            repairMarketValue(market, defaults, path + ".rarity",
                value -> value instanceof String rarity && rarityIds.contains(rarity));
            repairMarketValue(market, defaults, path + ".chance",
                value -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0);
            repairMarketValue(market, defaults, path + ".stock",
                value -> value instanceof Integer count && count >= 0);
            repairMarketValue(market, defaults, path + ".stock-mode",
                value -> value instanceof String text && validEnum(text,
                    me.foesio.foBlackmarket.market.StockMode.class));
            repairMarketValue(market, defaults, path + ".commands",
                value -> value instanceof List<?> list && list.stream().allMatch(String.class::isInstance));
            repairMarketItem(market, defaults, path + ".icon");
            repairMarketItem(market, defaults, path + ".reward-item");
            repairMarketValue(market, defaults, path + ".cost",
                value -> value instanceof ConfigurationSection);
            repairMarketValue(market, defaults, path + ".cost.type",
                value -> value instanceof String text && validEnum(text,
                    me.foesio.foBlackmarket.market.CostType.class));
            repairMarketValue(market, defaults, path + ".cost.money",
                value -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0);
            repairMarketItem(market, defaults, path + ".cost.item");
        }
    }

    private void repairMarketItem(YamlConfiguration market, YamlConfiguration defaults,
                                  String path) throws IOException {
        if (!market.isSet(path)) {
            return;
        }
        repairMarketValue(market, defaults, path, value -> value instanceof ConfigurationSection);
        if (!market.isConfigurationSection(path)) {
            return;
        }
        repairMarketValue(market, defaults, path + ".material", value -> {
            if (!(value instanceof String text)) return false;
            Material material = Material.matchMaterial(text);
            return material != null && (org.bukkit.Bukkit.getServer() == null
                ? material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR
                : material.isItem() && !material.isAir());
        });
        repairMarketValue(market, defaults, path + ".amount",
            value -> value instanceof Integer amount && amount >= 1);
        repairMarketValue(market, defaults, path + ".name", value -> value instanceof String);
        repairMarketValue(market, defaults, path + ".lore",
            value -> value instanceof List<?> lines && lines.stream().allMatch(String.class::isInstance));
        repairMarketValue(market, defaults, path + ".custom-model-data",
            value -> value instanceof Integer model && model >= 0);
        repairMarketValue(market, defaults, path + ".glowing", value -> value instanceof Boolean);
        repairMarketValue(market, defaults, path + ".glow", value -> value instanceof Boolean);
        repairMarketValue(market, defaults, path + ".item-flags", value -> value instanceof List<?> flags
            && flags.stream().allMatch(flag -> flag instanceof String text && validEnum(text, ItemFlag.class)));
    }

    void repairMarketValue(YamlConfiguration market, YamlConfiguration defaults,
                           String path, Predicate<Object> valid) throws IOException {
        if (!market.isSet(path) || valid.test(market.get(path))) {
            return;
        }
        Object replacement = defaults.get(path);
        if (replacement == null || !valid.test(replacement)) {
            throw new IOException("market.yml:" + path + " has no safe newest bundled default for " + market.get(path));
        }
        warnInvalid("market.yml:" + path, market.get(path), replacement);
        if (replacement instanceof ConfigurationSection section) {
            market.set(path, null);
            ConfigurationSection copy = market.createSection(path);
            for (String key : section.getKeys(true)) {
                if (!section.isConfigurationSection(key)) {
                    copy.set(key, section.get(key));
                }
            }
        } else {
            market.set(path, replacement);
        }
    }

    private static <T extends Enum<T>> boolean validEnum(String value, Class<T> type) {
        try {
            Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_'));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    void overlayLegacyConfigMessages(YamlConfiguration old, YamlConfiguration oldDefaults,
                                     YamlConfiguration target) {
        for (String root : List.of("colors", "messages", "titles")) {
            ConfigurationSection defaults = oldDefaults.getConfigurationSection(root);
            if (defaults == null) {
                continue;
            }
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || root.equals("titles") && key.equals("market")) {
                    continue;
                }
                String from = root + "." + key;
                String to = (root.equals("colors") ? "tokens" : root) + "." + key;
                if (!target.isSet(to)) {
                    continue; // Retired legacy key.
                }
                copy(old, oldDefaults, from, target, to, false);
            }
        }
        copy(old, oldDefaults, "prefix", target, "tokens.prefix", false);
    }

    void overlayLegacyDuration(YamlConfiguration old, YamlConfiguration oldDefaults,
                               YamlConfiguration messages, YamlConfiguration newest) {
        for (String unit : List.of("day", "hour", "minute", "second")) {
            String oldPath = "settings.time-format." + unit;
            if (!old.isSet(oldPath) || Objects.equals(old.get(oldPath), oldDefaults.get(oldPath))) {
                continue;
            }
            Object label = old.get(oldPath);
            if (!(label instanceof String text)) {
                warnInvalid("config.yml:" + oldPath, label,
                    newest.get("gui.duration-" + unit + "-other"));
                continue;
            }
            String template = "{value}" + text;
            replaceDefaultDuration(messages, newest, "gui.duration-" + unit + "-one", template);
            replaceDefaultDuration(messages, newest, "gui.duration-" + unit + "-other", template);
            if (unit.equals("second")) {
                replaceDefaultDuration(messages, newest, "gui.duration-zero", template);
            }
        }
    }

    private static void replaceDefaultDuration(YamlConfiguration messages, YamlConfiguration newest,
                                               String path, String template) {
        if (Objects.equals(messages.get(path), newest.get(path))) {
            messages.set(path, template);
        }
    }

    void overlayLegacyMarket(YamlConfiguration old, YamlConfiguration oldDefault,
                             YamlConfiguration target) throws IOException {
        Object size = old.get("gui.size");
        if (size != null && !Objects.equals(size, oldDefault.get("gui.size"))) {
            if (size instanceof Integer value && value >= 9 && value <= 54 && value % 9 == 0) {
                target.set("rows", value / 9);
            } else {
                warnInvalid("market.yml:gui.size", size, target.get("rows"));
            }
        }
        Object info = old.get("gui.info-slot");
        if (info != null && !Objects.equals(info, oldDefault.get("gui.info-slot"))) {
            if (info instanceof Integer slot && slot >= 0 && slot < target.getInt("rows") * 9) {
                target.set("decorations.info.slots", List.of(slot));
            } else {
                warnInvalid("market.yml:gui.info-slot", info, target.get("decorations.info.slots"));
            }
        }
        overlayLegacyFiller(old, oldDefault, target);
        List<Integer> oldSlots = oldSlotKeys(old);
        if (!oldSlots.isEmpty() && !oldSlots.equals(oldSlotKeys(oldDefault))) {
            target.set("entries.offers.slots", oldSlots);
        }
    }

    private void overlayLegacyFiller(YamlConfiguration old, YamlConfiguration oldDefault,
                                     YamlConfiguration target) throws IOException {
        ConfigurationSection section = old.getConfigurationSection("gui.filler");
        if (section == null) {
            target.set("filler.enabled", false);
            target.set("filler.material", null);
            return;
        }
        if (!section.contains("item-stack") && !section.contains("item-stack-bytes")) {
            for (String field : ITEM_FIELDS) {
                copy(old, oldDefault, "gui.filler." + field, target, "filler." + field,
                    field.equals("material"));
            }
            String skin = section.getString("skull.textures.skin");
            if (skin != null) {
                ConfigurationSection skull = section.getConfigurationSection("skull");
                ConfigurationSection textures = skull == null ? null : skull.getConfigurationSection("textures");
                if (skull == null || textures == null || skull.getKeys(false).stream()
                    .anyMatch(key -> !Set.of("textures", "owner-name", "owner-uuid", "owner-profile").contains(key))
                    || textures.getKeys(false).stream().anyMatch(key -> !Set.of("skin", "model").contains(key))) {
                    throw new IOException("market.yml:gui.filler.skull has metadata beyond the skin URL");
                }
                target.set("filler.skull-skin-url", skin);
            } else if (section.isConfigurationSection("skull")) {
                throw new IOException("market.yml:gui.filler.skull has no skin URL that Core can preserve");
            }
            rejectUnsupportedFillerFields(section);
            return;
        }

        ItemStack item = plugin.getMarketRepository().readItem(section);
        if (item == null || item.getType().isAir()) {
            throw new IOException("market.yml:gui.filler serialized item cannot be read safely");
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getEnchants().isEmpty() || !meta.getPersistentDataContainer().isEmpty()
            || meta instanceof Damageable damageable && damageable.hasDamage()
            || meta.hasAttributeModifiers()) {
            throw new IOException("market.yml:gui.filler contains item metadata Core fixed items cannot preserve");
        }
        target.set("filler.enabled", true);
        target.set("filler.material", item.getType().name());
        target.set("filler.amount", item.getAmount());
        target.set("filler.name", meta.hasDisplayName() ? meta.getDisplayName() : null);
        target.set("filler.lore", meta.hasLore() ? meta.getLore() : null);
        target.set("filler.custom-model-data", meta.hasCustomModelData() ? meta.getCustomModelData() : null);
        target.set("filler.item-flags", meta.getItemFlags().stream().map(Enum::name).sorted().toList());
        target.set("filler.glow", meta.hasEnchantmentGlintOverride() && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride()));
        if (meta instanceof SkullMeta skull) {
            if (skull.getNoteBlockSound() != null) {
                throw new IOException("market.yml:gui.filler has a custom note block sound Core cannot preserve");
            }
            var profile = skull.getOwnerProfile();
            var skin = profile == null ? null : profile.getTextures().getSkin();
            if ((profile != null || skull.hasOwner()) && skin == null) {
                throw new IOException("market.yml:gui.filler skull profile has no transferable skin URL");
            }
            target.set("filler.skull-skin-url", skin == null ? null : skin.toString());
        }
    }

    private static void rejectUnsupportedFillerFields(ConfigurationSection section) throws IOException {
        Set<String> known = Set.of("material", "amount", "name", "lore", "custom-model-data",
            "glow", "glowing", "item-flags", "skull");
        for (String field : section.getKeys(false)) {
            if (!known.contains(field)) {
                throw new IOException("market.yml:gui.filler." + field
                    + " cannot be preserved in Core fixed item metadata");
            }
        }
        if (section.contains("glowing") && section.getBoolean("glowing")) {
            throw new IOException("market.yml:gui.filler.glowing uses an enchantment effect Core cannot preserve");
        }
    }

    private List<Integer> oldSlotKeys(YamlConfiguration market) {
        var section = market.getConfigurationSection("gui.slot-rarities");
        if (section == null) {
            return List.of();
        }
        List<Integer> slots = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                slots.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                warnInvalid("market.yml:gui.slot-rarities." + key, section.get(key), null);
            }
        }
        slots.sort(Comparator.naturalOrder());
        return slots;
    }

    private void overlayPlayer(YamlConfiguration old, YamlConfiguration oldDefault, YamlConfiguration target,
                               YamlConfiguration messages, YamlConfiguration messageDefault) {
        copy(old, oldDefault, "title", target, "title", true);
        copyItem(old, oldDefault, "items.info", target, "decorations.info");
        copy(old, oldDefault, "offer-item.fallback-material", target, "states.fallback-offer.material", true);
        copy(old, oldDefault, "offer-item.lore", target, "entries.offers.source-item.lore.lines", false);
        removeRetiredPlayerCommandsLine(target);
        copyMessage(old, oldDefault, "offer-item.buy-line", messages, messageDefault,
            "gui.player-buy-line");
        copyMessage(old, oldDefault, "offer-item.sold-out-line", messages, messageDefault,
            "gui.player-sold-out-line");
        reportRetiredName(old, oldDefault, "offer-item.name", PLAYER_FILE);
    }

    private void overlayCommand(YamlConfiguration old, YamlConfiguration oldDefault, YamlConfiguration target,
                                YamlConfiguration messages, YamlConfiguration messageDefault) {
        copy(old, oldDefault, "title", target, "title", true);
        copy(old, oldDefault, "rows", target, "rows", true);
        copy(old, oldDefault, "slots.timer", target, "decorations.timer.slots", true, true);
        copy(old, oldDefault, "slots.rarities", target, "entries.rarities.slots", true);
        copy(old, oldDefault, "slots.offers", target, "entries.offers.slots", true);
        copy(old, oldDefault, "slots.previous-page", target, "entries.previous-page.slots", true, true);
        copy(old, oldDefault, "slots.next-page", target, "entries.next-page.slots", true, true);
        copyItem(old, oldDefault, "items.timer", target, "decorations.timer");
        copyItem(old, oldDefault, "items.empty-offer", target, "entries.offers.empty-item");
        copyItem(old, oldDefault, "items.previous-page", target, "entries.previous-page.item");
        copyItem(old, oldDefault, "items.next-page", target, "entries.next-page.item");
        copy(old, oldDefault, "rarity-item.lore", target, "entries.rarities.source-item.lore.lines", false);
        copy(old, oldDefault, "offer-item.lore", target, "entries.offers.source-item.lore.lines", false);
        copy(old, oldDefault, "offer-item.fallback-material", target, "states.fallback-offer.material", true);
        copyMessage(old, oldDefault, "offer-item.preview-line", messages, messageDefault,
            "gui.command-preview-line");
        copyMessage(old, oldDefault, "offer-preview-lore", messages, messageDefault,
            "gui.command-preview-line");
        Map<String, String> messagePaths = Map.of(
            "rarity-item.selected", "gui.rarity-selected",
            "rarity-item.unselected", "gui.rarity-unselected",
            "rarity-item.selected-click-line", "gui.rarity-selected-click-line",
            "rarity-item.unselected-click-line", "gui.rarity-unselected-click-line",
            "labels.no-rarity", "gui.no-rarity",
            "labels.showing-no-offers", "gui.showing-no-offers",
            "labels.showing-offers", "gui.showing-offers");
        messagePaths.forEach((from, to) -> copyMessage(old, oldDefault, from, messages, messageDefault, to));
        reportRetiredName(old, oldDefault, "rarity-item.name", COMMAND_FILE);
        reportRetiredName(old, oldDefault, "offer-item.name", COMMAND_FILE);
    }

    /** A new control yields its bundled slot to a valid older owner layout. */
    static void relocateNewRarityControls(YamlConfiguration command) throws IOException {
        int rows = command.getInt("rows", 6);
        int size = rows >= 1 && rows <= 6 ? rows * 9 : 54;
        Set<Integer> occupied = new java.util.HashSet<>();
        for (String path : List.of("decorations.timer.slots", "entries.rarities.slots",
            "entries.offers.slots", "entries.previous-page.slots", "entries.next-page.slots")) {
            occupied.addAll(command.getIntegerList(path));
        }
        for (String entry : List.of("previous-rarity-page", "next-rarity-page")) {
            String path = "entries." + entry + ".slots";
            List<Integer> configured = command.getIntegerList(path);
            if (configured.size() != 1) throw new IOException(COMMAND_FILE + ":" + path + " is invalid");
            int preferred = configured.getFirst();
            int chosen = -1;
            for (int distance = 0; distance < size && chosen < 0; distance++) {
                for (int candidate : new int[] {preferred - distance, preferred + distance}) {
                    if (candidate >= 0 && candidate < size && !occupied.contains(candidate)) {
                        chosen = candidate;
                        break;
                    }
                }
            }
            if (chosen < 0) throw new IOException(COMMAND_FILE + ": no free slot for " + entry);
            command.set(path, List.of(chosen));
            occupied.add(chosen);
        }
    }

    private void reportRetiredName(YamlConfiguration old, YamlConfiguration defaults, String path, String file) {
        if (old.isSet(path) && !Objects.equals(old.get(path), defaults.get(path))) {
            plugin.getLogger().warning(file + ":" + path + " is retired by the owner-approved real icon name rule; "
                + "the original value is retained in the upgrade backup.");
        }
    }

    private static void copyItem(YamlConfiguration old, YamlConfiguration oldDefault, String from,
                                 YamlConfiguration target, String to) {
        for (String field : ITEM_FIELDS) {
            copy(old, oldDefault, from + "." + field, target, to + "." + field,
                field.equals("material"));
        }
    }

    private static void copyMessage(YamlConfiguration old, YamlConfiguration oldDefault, String from,
                                    YamlConfiguration messages, YamlConfiguration messageDefault, String to) {
        if (!old.isSet(from)) {
            if (oldDefault.isSet(from)) {
                messages.set(to, null);
            }
        } else if (!Objects.equals(old.get(from), oldDefault.get(from))) {
            messages.set(to, old.get(from));
        } else if (!messages.isSet(to)) {
            messages.set(to, messageDefault.get(to));
        }
    }

    private static void copy(YamlConfiguration old, YamlConfiguration oldDefault, String from,
                             YamlConfiguration target, String to, boolean required) {
        copy(old, oldDefault, from, target, to, required, false);
    }

    private static void copy(YamlConfiguration old, YamlConfiguration oldDefault, String from,
                             YamlConfiguration target, String to, boolean required, boolean singleSlot) {
        if (!old.isSet(from)) {
            if (!required && oldDefault.isSet(from)) {
                target.set(to, null);
            }
            return;
        }
        Object value = old.get(from);
        if (Objects.equals(value, oldDefault.get(from))) {
            return;
        }
        target.set(to, singleSlot ? List.of(value) : value);
    }

    private void installNewMessageKeys(YamlConfiguration messages, YamlConfiguration defaults,
                                       SourceSnapshot source) {
        Set<String> introduced = Set.of("gui.cost-money", "gui.cost-item", "gui.cost-both",
            "gui.cost-missing-item", "gui.cost-unnamed-item", "gui.number-locale",
            "gui.duration-day-one", "gui.duration-day-other", "gui.duration-hour-one",
            "gui.duration-hour-other", "gui.duration-minute-one", "gui.duration-minute-other",
            "gui.duration-second-one", "gui.duration-second-other", "gui.duration-zero",
            "gui.duration-separator");
        for (String path : defaults.getConfigurationSection("gui").getKeys(true)) {
            if (defaults.isConfigurationSection("gui." + path)) {
                continue;
            }
            String full = "gui." + path;
            if (!messages.isSet(full) && (introduced.contains(full) || source.originalVersion() < 4
                || !source.existed(COMMAND_FILE) && !source.existed(PLAYER_FILE))) {
                messages.set(full, defaults.get(full));
            }
        }
    }

    static void upgradeChestSprite(YamlConfiguration messages) {
        String path = "messages.opengui-success";
        String text = messages.getString(path);
        if (text != null && text.contains(":chest:")) {
            messages.set(path, text.replace(":chest:", ":chest_minecart:"));
        }
    }

    void repairMessages(YamlConfiguration messages, YamlConfiguration defaults) {
        for (String root : List.of("messages", "tokens", "gui")) {
            var section = defaults.getConfigurationSection(root);
            if (section == null) {
                continue;
            }
            if (messages.isSet(root) && !messages.isConfigurationSection(root)) {
                warnInvalid("messages.yml:" + root, messages.get(root), section.getValues(true));
                messages.set(root, null);
                for (String key : section.getKeys(true)) {
                    if (!section.isConfigurationSection(key)) {
                        messages.set(root + "." + key, section.get(key));
                    }
                }
            }
            for (String key : section.getKeys(true)) {
                String path = root + "." + key;
                if (defaults.isConfigurationSection(path) || !messages.isSet(path)) {
                    continue;
                }
                Object value = messages.get(path);
                if (!(value instanceof String text) || path.equals("gui.number-locale")
                    && !validLanguageTag(text)) {
                    warnInvalid("messages.yml:" + path, value, defaults.get(path));
                    messages.set(path, defaults.get(path));
                }
            }
        }
    }

    private static boolean validLanguageTag(String tag) {
        if (tag.isBlank()) return true;
        try {
            new java.util.Locale.Builder().setLanguageTag(tag);
            return true;
        } catch (java.util.IllformedLocaleException exception) {
            return false;
        }
    }

    private void alignMarketAssignments(YamlConfiguration market, YamlConfiguration player,
                                        YamlConfiguration defaults) {
        var original = market.getConfigurationSection("gui.slot-rarities");
        var validRarities = market.getConfigurationSection("rarities").getKeys(false);
        List<String> rarities = new ArrayList<>();
        Map<Integer, String> bySlot = new LinkedHashMap<>();
        if (original != null) {
            List<String> keys = new ArrayList<>(original.getKeys(false));
            keys.sort(Comparator.comparingInt(key -> {
                try { return Integer.parseInt(key); } catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
            }));
            for (String key : keys) {
                Object value = original.get(key);
                Integer numericSlot = null;
                try {
                    numericSlot = Integer.parseInt(key);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (!(value instanceof String rarity) || rarity.isBlank()
                    || !validRarities.contains(rarity)) {
                    Object fallback = defaults.get("gui.slot-rarities." + numericSlot);
                    warnInvalid("market.yml:gui.slot-rarities." + key, value, fallback);
                    value = fallback;
                }
                if (value instanceof String rarity && validRarities.contains(rarity)) {
                    rarities.add(rarity);
                    bySlot.put(numericSlot, rarity);
                }
            }
        }
        market.set("gui.size", null);
        market.set("gui.info-slot", null);
        market.set("gui.close-slot", null);
        market.set("gui.filler", null);
        market.set("gui.slot-rarities", null);
        List<Integer> slots = player.getIntegerList("entries.offers.slots");
        if (slots.containsAll(bySlot.keySet())) {
            for (int slot : slots) {
                if (bySlot.containsKey(slot)) {
                    market.set("gui.slot-rarities." + slot, bySlot.get(slot));
                }
            }
        } else {
            for (int index = 0; index < Math.min(slots.size(), rarities.size()); index++) {
                market.set("gui.slot-rarities." + slots.get(index), rarities.get(index));
            }
        }
    }

    void repairGui(String file, String menuId, YamlConfiguration candidate,
                   YamlConfiguration defaults, PublicGuiContract contract) throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            try {
                PublicGuiDefinition definition = PublicGuiSchema.parse(menuId, file, candidate, contract);
                Set<String> usedActions = new java.util.HashSet<>();
                definition.buttons().values().forEach(button -> {
                    if (button.action() != null) usedActions.add(button.action());
                    usedActions.addAll(button.actions().values());
                });
                definition.entries().values().forEach(entry -> usedActions.addAll(entry.actions().values()));
                if (!usedActions.containsAll(contract.actions())) {
                    throw new IOException(file + ": required action links are missing: " + contract.actions());
                }
                return;
            } catch (GuiDefinitionException problem) {
                String path = problem.key().replaceFirst("\\[.*$", "");
                if (repairListElement(file, candidate, defaults, problem, path)) {
                    continue;
                }
                if (!defaults.isSet(path)) {
                    if (candidate.isSet(path)) {
                        warnInvalid(file + ":" + path, candidate.get(path), null);
                        candidate.set(path, null);
                        continue;
                    }
                    throw new IOException(file + ":" + path + " has no safe bundled default: "
                        + problem.getMessage(), problem);
                }
                Object invalid = candidate.get(path);
                Object replacement = defaults.get(path);
                if (Objects.deepEquals(invalid, replacement)) {
                    throw new IOException(file + ":" + path + " remains invalid with its bundled default", problem);
                }
                warnInvalid(file + ":" + path, invalid, replacement);
                candidate.set(path, replacement);
            }
        }
        throw new IOException(file + ": too many invalid fields to isolate safely");
    }

    private boolean repairListElement(String file, YamlConfiguration candidate,
                                      YamlConfiguration defaults, GuiDefinitionException problem,
                                      String path) {
        Object current = candidate.get(path);
        if (!(current instanceof List<?> raw)) {
            return false;
        }
        int index = -1;
        Matcher element = LIST_ELEMENT.matcher(problem.key());
        if (element.matches()) {
            index = Integer.parseInt(element.group(2));
        } else {
            Matcher overlap = OVERLAPPING_SLOT.matcher(problem.getMessage());
            if (overlap.find()) {
                int slot = Integer.parseInt(overlap.group(1));
                index = raw.indexOf(slot);
            }
        }
        if (index < 0 || index >= raw.size()) {
            return false;
        }
        List<?> bundled = defaults.getList(path);
        List<Object> repaired = new ArrayList<>(raw);
        Object invalid = repaired.get(index);
        Object replacement = replacementListElement(repaired, bundled, index, path.endsWith(".slots"));
        if (replacement == null) {
            repaired.remove(index);
        } else {
            repaired.set(index, replacement);
        }
        if (Objects.equals(repaired, raw)) {
            return false;
        }
        warnInvalid(file + ":" + path + "[" + index + "]", invalid, replacement);
        candidate.set(path, repaired);
        return true;
    }

    static Object replacementListElement(List<?> current, List<?> bundled, int index, boolean slots) {
        if (bundled == null || index >= bundled.size()) {
            return null;
        }
        Object replacement = bundled.get(index);
        if (!slots) {
            return Objects.equals(current.get(index), replacement) ? null : replacement;
        }
        if (!Objects.equals(current.get(index), replacement) && !current.contains(replacement)) {
            return replacement;
        }
        for (Object option : bundled) {
            if (!current.contains(option)) {
                return option;
            }
        }
        return null;
    }

    void repairSounds(YamlConfiguration sounds, YamlConfiguration defaults) {
        for (String event : PUBLIC_SOUND_PATHS) {
            if (!sounds.isSet(event)) {
                continue;
            }
            Object section = sounds.get(event);
            if (!(section instanceof org.bukkit.configuration.ConfigurationSection)) {
                ConfigurationSection replacement = defaults.getConfigurationSection(event);
                warnInvalid("sounds.yml:" + event, section,
                    replacement == null ? null : replacement.getValues(false));
                sounds.set(event, null);
                if (replacement != null) {
                    for (String field : replacement.getKeys(true)) {
                        if (!replacement.isConfigurationSection(field)) {
                            sounds.set(event + "." + field, replacement.get(field));
                        }
                    }
                }
                continue;
            }
            for (String field : List.of("enabled", "sound", "key", "category", "volume", "pitch")) {
                String path = event + "." + field;
                if (!sounds.isSet(path)) {
                    continue;
                }
                Object value = sounds.get(path);
                boolean valid = switch (field) {
                    case "enabled" -> value instanceof Boolean;
                    case "sound", "key" -> value instanceof String;
                    case "category" -> value instanceof String text && validCategory(text);
                    case "volume" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= 0;
                    case "pitch" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= 0.5 && number.doubleValue() <= 2;
                    default -> true;
                };
                if (!valid) {
                    warnInvalid("sounds.yml:" + path, value, defaults.get(path));
                    sounds.set(path, defaults.get(path));
                }
            }
        }
    }

    private static boolean validCategory(String value) {
        try {
            org.bukkit.SoundCategory.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void warnInvalid(String path, Object invalid, Object replacement) {
        java.util.logging.Logger logger = plugin == null
            ? java.util.logging.Logger.getLogger(PublicMarketMigration.class.getName())
            : plugin.getLogger();
        logger.warning("Public GUI upgrade " + path + "=" + invalid
            + " is invalid; using newest bundled default " + replacement + ".");
    }

    private YamlConfiguration bundled(String resource) throws IOException {
        return parse(resourceBytes(resource), resource);
    }

    private byte[] resourceBytes(String resource) throws IOException {
        try (InputStream stream = plugin == null
            ? PublicMarketMigration.class.getClassLoader().getResourceAsStream(resource)
            : plugin.getResource(resource)) {
            if (stream == null) {
                throw new IOException("Missing bundled resource " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private static YamlConfiguration parse(byte[] bytes, String label) throws IOException {
        if (bytes == null) {
            throw new IOException(label + " is missing");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            return yaml;
        } catch (InvalidConfigurationException exception) {
            throw new IOException(label + " cannot be parsed: " + exception.getMessage(), exception);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static final class Transaction {
        private final Path root;
        private final SourceSnapshot source;
        private final Map<String, byte[]> staged;
        private final Map<Path, byte[]> before = new LinkedHashMap<>();
        private boolean attempted;

        Transaction(Path root, SourceSnapshot source, Map<String, byte[]> staged) throws IOException {
            this.root = root;
            this.source = source;
            this.staged = staged;
            for (String name : staged.keySet()) {
                Path path = root.resolve(name);
                before.put(path, Files.isRegularFile(path) ? Files.readAllBytes(path) : null);
            }
            Path config = root.resolve("config.yml");
            before.putIfAbsent(config, Files.isRegularFile(config) ? Files.readAllBytes(config) : null);
        }

        void commit() throws IOException {
            attempted = true;
            for (String name : staged.keySet()) {
                byte[] original = source.original(name);
                if (original == null) {
                    continue;
                }
                Path file = root.resolve(name);
                Path backup = file.resolveSibling(file.getFileName() + ".gui-v"
                    + source.originalVersion() + "-backup");
                if (!Files.exists(backup)) {
                    Files.createDirectories(backup.getParent());
                    Files.write(backup, original);
                }
            }
            byte[] config = source.original("config.yml");
            if (config != null) {
                Path file = root.resolve("config.yml");
                Path backup = file.resolveSibling("config.yml.gui-v" + source.originalVersion() + "-backup");
                if (!Files.exists(backup)) {
                    Files.write(backup, config);
                }
            }
            try {
                for (Map.Entry<String, byte[]> change : staged.entrySet()) {
                    replace(root.resolve(change.getKey()), change.getValue());
                }
            } catch (IOException exception) {
                rollback();
                throw exception;
            }
        }

        void rollback() throws IOException {
            if (!attempted) {
                return;
            }
            IOException failed = null;
            for (Map.Entry<Path, byte[]> original : before.entrySet()) {
                try {
                    if (original.getValue() == null) {
                        Files.deleteIfExists(original.getKey());
                    } else {
                        replace(original.getKey(), original.getValue());
                    }
                } catch (IOException exception) {
                    if (failed == null) failed = exception; else failed.addSuppressed(exception);
                }
            }
            if (failed != null) {
                throw failed;
            }
        }

        private static void replace(Path target, byte[] data) throws IOException {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".public-gui-", ".staged");
            try {
                Files.write(temporary, data);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
