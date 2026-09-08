package me.foesio.foBlackmarket.gui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.message.FoMessageService;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class GuiConfigManager {

    private static final String PLAYER_MARKET_RESOURCE = "guis/player-market.yml";
    private static final String COMMAND_MARKET_RESOURCE = "guis/command-market.yml";

    private final FoBlackmarket plugin;
    private PlayerMarketGuiConfig playerMarket;
    private CommandMarketGuiConfig commandMarket;

    public GuiConfigManager(FoBlackmarket plugin) {
        this.plugin = plugin;
    }

    public void ensureDefaults(FileConfiguration oldConfig) {
        ensureGuiFile(PLAYER_MARKET_RESOURCE, oldConfig);
        ensureGuiFile(COMMAND_MARKET_RESOURCE, oldConfig);
    }

    public void reload() {
        this.playerMarket = loadPlayerMarket();
        this.commandMarket = loadCommandMarket();
    }

    public PlayerMarketGuiConfig playerMarket() {
        return playerMarket;
    }

    public CommandMarketGuiConfig commandMarket() {
        return commandMarket;
    }

    private void ensureGuiFile(String resourcePath, FileConfiguration oldConfig) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        boolean created = false;
        if (file.exists()) {
            created = false;
        } else {
            plugin.saveResource(resourcePath, false);
            created = true;
        }

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        if (oldConfig != null && oldConfig.contains("titles.market")) {
            migrated.set("title", oldConfig.getString("titles.market", "&8Black Market"));
            changed = true;
        }
        if (!created && COMMAND_MARKET_RESOURCE.equals(resourcePath)) {
            changed |= migrateCommandMarketStyle(migrated);
        }
        if (!changed) {
            return;
        }
        try {
            migrated.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("FoBlackmarket: Could not migrate old market title into " + resourcePath + ": " + exception.getMessage());
        }
    }

    private boolean migrateCommandMarketStyle(YamlConfiguration config) {
        boolean changed = false;
        changed |= replaceExact(config, "items.timer.name", "#03fc88Market Reset", ":clock: {theme}&lMARKET RESET");
        changed |= replaceExact(config, "items.timer.lore", List.of(
            "#ffffffTime until reset:",
            "#03fc88{time_remaining}",
            "#ffffffSelected rarity: {selected_rarity}",
            "{showing_offers}"
        ), List.of(
            "&8ɪɴꜰᴏ",
            " ",
            "&eⓘ Information ↓",
            "&7&l | &fTime until reset: {theme}{time_remaining}",
            "&7&l | &fSelected rarity: {theme}{selected_rarity}",
            "&7&l | &f{showing_offers}"
        ));
        changed |= replaceExact(config, "rarity-item.lore", List.of(
            "#ffffffOffers: #03fc88{offer_count}",
            "{selection_status}"
        ), List.of(
            "Offers: {offer_count}",
            "{selection_status}"
        ));
        changed |= replaceExact(config, "rarity-item.selected", "#3ecf8eSelected", "Selected");
        changed |= replaceExact(config, "rarity-item.unselected", "#a7b8b0Click to view this rarity.", "Click to view this rarity.");
        return changed;
    }

    private boolean replaceExact(YamlConfiguration config, String path, Object oldValue, Object newValue) {
        if (!Objects.equals(config.get(path), oldValue)) {
            return false;
        }
        config.set(path, newValue);
        return true;
    }

    private PlayerMarketGuiConfig loadPlayerMarket() {
        YamlPair yaml = loadYaml(PLAYER_MARKET_RESOURCE);
        return new PlayerMarketGuiConfig(
            GuiTitles.format(string(yaml, "title", "&8Black Market")),
            readItem(yaml, "items.info", new ConfiguredGuiItem(
                Material.CLOCK,
                1,
                "#03fc88Market Info",
                List.of("#ffffffNext restock:", "#03fc88{time_remaining}", "#a7b8b0Use your selected offer to buy stock."),
                false,
                null,
                List.of()
            )),
            material(string(yaml, "offer-item.fallback-material", "CHEST"), Material.CHEST),
            string(yaml, "offer-item.name", "{reward}"),
            stringList(yaml, "offer-item.lore", List.of(
                "#ffffffRarity: {rarity}",
                "#ffffffStock: #03fc88{stock}",
                "#ffffffCost: #03fc88{cost}",
                "#ffffffChance: #03fc88{chance}%",
                "{commands_line}",
                "{status_line}"
            )),
            string(yaml, "offer-item.commands-line", "#ffffffCommands: #03fc88{commands}"),
            string(yaml, "offer-item.buy-line", "#3ecf8eClick to buy."),
            string(yaml, "offer-item.sold-out-line", "#ff5d73Sold out.")
        );
    }

    private CommandMarketGuiConfig loadCommandMarket() {
        YamlPair yaml = loadYaml(COMMAND_MARKET_RESOURCE);
        int size = rowsToSize(integer(yaml, "rows", 6));
        return new CommandMarketGuiConfig(
            GuiTitles.format(string(yaml, "title", "&8Black Market")),
            size,
            slot(yaml, "slots.timer", 4, size),
            slots(yaml, "slots.rarities", List.of(11, 12, 13, 14, 15, 16, 17), size),
            slots(yaml, "slots.offers", List.of(28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43), size),
            slot(yaml, "slots.previous-page", 36, size),
            slot(yaml, "slots.next-page", 44, size),
            readItem(yaml, "items.timer", new ConfiguredGuiItem(
                Material.CLOCK,
                1,
                "#03fc88Market Reset",
                List.of("#ffffffTime until reset:", "#03fc88{time_remaining}", "#ffffffSelected rarity: {selected_rarity}", "{showing_offers}"),
                false,
                null,
                List.of()
            )),
            readItem(yaml, "items.empty-offer", new ConfiguredGuiItem(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                1,
                " ",
                List.of(),
                false,
                null,
                List.of()
            )),
            stringList(yaml, "rarity-item.lore", List.of("#ffffffOffers: #03fc88{offer_count}", "{selection_status}")),
            string(yaml, "rarity-item.selected", "#3ecf8eSelected"),
            string(yaml, "rarity-item.unselected", "#a7b8b0Click to view this rarity."),
            string(yaml, "offer-preview-lore", "#a7b8b0Preview only.")
        );
    }

    private ConfiguredGuiItem readItem(YamlPair yaml, String path, ConfiguredGuiItem fallback) {
        ConfigurationSection section = section(yaml.actual(), yaml.defaults(), path);
        if (section == null) {
            return fallback;
        }
        Material material = material(section.getString("material", fallback.material().name()), fallback.material());
        int amount = Math.max(1, Math.min(64, section.getInt("amount", fallback.amount())));
        String name = section.getString("name", fallback.name());
        List<String> lore = section.contains("lore") ? section.getStringList("lore") : fallback.lore();
        boolean glowing = section.getBoolean("glowing", fallback.glowing());
        Integer customModelData = fallback.customModelData();
        if (section.contains("custom-model-data")) {
            customModelData = Math.max(0, section.getInt("custom-model-data"));
        }
        List<ItemFlag> itemFlags = readItemFlags(section, fallback.itemFlags());
        return new ConfiguredGuiItem(material, amount, name, lore, glowing, customModelData, itemFlags);
    }

    private List<ItemFlag> readItemFlags(ConfigurationSection section, List<ItemFlag> fallback) {
        if (!section.contains("item-flags")) {
            return fallback;
        }
        List<ItemFlag> flags = new ArrayList<>();
        for (String rawFlag : section.getStringList("item-flags")) {
            try {
                flags.add(ItemFlag.valueOf(rawFlag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("FoBlackmarket: Invalid GUI item flag '" + rawFlag + "'.");
            }
        }
        return List.copyOf(flags);
    }

    private YamlPair loadYaml(String resourcePath) {
        return new YamlPair(
            YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), resourcePath)),
            loadResource(resourcePath)
        );
    }

    private FileConfiguration loadResource(String resourcePath) {
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null) {
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().warning("FoBlackmarket: Could not read default " + resourcePath + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private String string(YamlPair yaml, String path, String fallback) {
        if (yaml.actual().contains(path)) {
            return yaml.actual().getString(path, fallback);
        }
        return yaml.defaults().getString(path, fallback);
    }

    private int integer(YamlPair yaml, String path, int fallback) {
        if (yaml.actual().contains(path)) {
            return yaml.actual().getInt(path, fallback);
        }
        return yaml.defaults().getInt(path, fallback);
    }

    private List<String> stringList(YamlPair yaml, String path, List<String> fallback) {
        if (yaml.actual().contains(path)) {
            return yaml.actual().getStringList(path);
        }
        if (yaml.defaults().contains(path)) {
            return yaml.defaults().getStringList(path);
        }
        return fallback;
    }

    private ConfigurationSection section(FileConfiguration actual, FileConfiguration defaults, String path) {
        ConfigurationSection actualSection = actual.getConfigurationSection(path);
        if (actualSection != null) {
            return actualSection;
        }
        return defaults.getConfigurationSection(path);
    }

    private Material material(String raw, Material fallback) {
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        if (material == null) {
            plugin.getLogger().warning("FoBlackmarket: Invalid GUI material '" + raw + "', using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    private int rowsToSize(int rows) {
        if (rows != 6) {
            plugin.getLogger().warning("FoBlackmarket: command-market.yml rows must stay 6 for the configured browser layout.");
        }
        return 54;
    }

    private int slot(YamlPair yaml, String path, int fallback, int size) {
        int slot = integer(yaml, path, fallback);
        if (slot < 0 || slot >= size) {
            plugin.getLogger().warning("FoBlackmarket: Invalid GUI slot '" + path + "=" + slot + "', using " + fallback + ".");
            return fallback;
        }
        return slot;
    }

    private int[] slots(YamlPair yaml, String path, List<Integer> fallback, int size) {
        List<Integer> rawSlots = yaml.actual().contains(path) ? yaml.actual().getIntegerList(path) : yaml.defaults().getIntegerList(path);
        if (rawSlots.isEmpty()) {
            rawSlots = fallback;
        }

        List<Integer> validSlots = new ArrayList<>();
        for (Integer slot : rawSlots) {
            if (slot == null || slot < 0 || slot >= size) {
                plugin.getLogger().warning("FoBlackmarket: Invalid GUI slot in '" + path + "': " + slot + ".");
                continue;
            }
            validSlots.add(slot);
        }
        if (validSlots.isEmpty()) {
            validSlots = fallback;
        }
        int[] result = new int[validSlots.size()];
        for (int index = 0; index < validSlots.size(); index++) {
            result[index] = validSlots.get(index);
        }
        return result;
    }

    private record YamlPair(FileConfiguration actual, FileConfiguration defaults) {
    }

    public record PlayerMarketGuiConfig(
        String title,
        ConfiguredGuiItem infoItem,
        Material offerFallbackMaterial,
        String offerName,
        List<String> offerLore,
        String commandsLine,
        String buyLine,
        String soldOutLine
    ) {
    }

    public record CommandMarketGuiConfig(
        String title,
        int size,
        int timerSlot,
        int[] raritySlots,
        int[] offerSlots,
        int previousPageSlot,
        int nextPageSlot,
        ConfiguredGuiItem timerItem,
        ConfiguredGuiItem emptyOfferItem,
        List<String> rarityLore,
        String raritySelectedLine,
        String rarityUnselectedLine,
        String offerPreviewLore
    ) {
        public int offerPageSize() {
            return offerSlots.length;
        }
    }

    public record ConfiguredGuiItem(
        Material material,
        int amount,
        String name,
        List<String> lore,
        boolean glowing,
        Integer customModelData,
        List<ItemFlag> itemFlags
    ) {
        public ItemStack toItem(FoMessageService messages, Player viewer, Map<String, String> placeholders) {
            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String renderedName = render(messages, viewer, name, placeholders);
                List<String> renderedLore = new ArrayList<>();
                for (String line : lore) {
                    String rendered = render(messages, viewer, line, placeholders);
                    if (!rendered.isBlank()) {
                        renderedLore.add(rendered);
                    }
                }
                if (!DialogIcons.applyItemMeta(meta, viewer, renderedName, renderedLore)) {
                    meta.setDisplayName(DialogIcons.fallbackText(renderedName));
                    meta.setLore(renderedLore.stream().map(DialogIcons::fallbackText).toList());
                }
                if (customModelData != null) {
                    meta.setCustomModelData(customModelData);
                }
                for (ItemFlag flag : itemFlags) {
                    meta.addItemFlags(flag);
                }
                if (glowing) {
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                item.setItemMeta(meta);
            }
            if (glowing) {
                Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
                if (enchantment != null) {
                    item.addUnsafeEnchantment(enchantment, 1);
                }
            }
            return item;
        }

        private static String render(FoMessageService messages, Player viewer, String template, Map<String, String> placeholders) {
            return viewer == null
                ? messages.renderTemplate(template, placeholders)
                : messages.renderTemplateForViewer(viewer, template, placeholders);
        }
    }
}
