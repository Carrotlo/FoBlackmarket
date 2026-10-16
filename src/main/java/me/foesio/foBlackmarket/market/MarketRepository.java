package me.foesio.foBlackmarket.market;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.foesio.core.item.FoItemStacks;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public final class MarketRepository {

    private static final String SERIALIZED_ITEM_BYTES_PATH = "item-stack-bytes";
    private static final String SERIALIZED_ITEM_PATH = "item-stack";

    private final FoBlackmarket plugin;
    private final File file;

    public MarketRepository(FoBlackmarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
    }

    MarketRepository(File file) {
        this.plugin = null;
        this.file = file;
    }

    public void ensureExists() {
        if (!file.exists()) {
            plugin.saveResource("market.yml", false);
        }
    }

    public MarketData load() {
        return readMarket(YamlConfiguration.loadConfiguration(file));
    }

    /** Read and check a proposed current-schema market before a GUI cache swap. */
    public record PreparedMarket(MarketData data, MarketState state) {}

    public PreparedMarket loadStrict(List<Integer> offerSlots) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("market.yml cannot be parsed: " + exception.getMessage(), exception);
        }
        validateCurrent(yaml, offerSlots);
        return new PreparedMarket(readMarket(yaml), readState(yaml));
    }

    private MarketData readMarket(YamlConfiguration yaml) {

        ConfigurationSection guiSection = yaml.getConfigurationSection("gui");
        int size = clampSize(guiSection == null ? 54 : guiSection.getInt("size", 54));
        int infoSlot = guiSection == null ? 4 : guiSection.getInt("info-slot", 4);
        int closeSlot = guiSection == null ? 49 : guiSection.getInt("close-slot", 49);
        ItemStack filler = readItem(guiSection == null ? null : guiSection.getConfigurationSection("filler"));

        Map<Integer, String> slotRarities = new LinkedHashMap<>();
        if (guiSection != null) {
            ConfigurationSection slots = guiSection.getConfigurationSection("slot-rarities");
            if (slots != null) {
                for (String key : slots.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(key);
                        slotRarities.put(slot, slots.getString(key, ""));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        Map<String, RarityDefinition> rarities = new LinkedHashMap<>();
        ConfigurationSection raritySection = yaml.getConfigurationSection("rarities");
        if (raritySection != null) {
            for (String rarityId : raritySection.getKeys(false)) {
                ConfigurationSection section = raritySection.getConfigurationSection(rarityId);
                if (section == null) {
                    continue;
                }
                rarities.put(rarityId, new RarityDefinition(
                    rarityId,
                    section.getString("name", rarityId),
                    readItem(section.getConfigurationSection("icon"))
                ));
            }
        }

        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        ConfigurationSection rewardSection = yaml.getConfigurationSection("rewards");
        if (rewardSection != null) {
            for (String rewardId : rewardSection.getKeys(false)) {
                ConfigurationSection section = rewardSection.getConfigurationSection(rewardId);
                if (section == null) {
                    continue;
                }
                ConfigurationSection costSection = section.getConfigurationSection("cost");
                CostDefinition cost = new CostDefinition(
                    parseCostType(costSection == null ? "MONEY" : costSection.getString("type", "MONEY")),
                    costSection == null ? 0.0D : costSection.getDouble("money", 0.0D),
                    readItem(costSection == null ? null : costSection.getConfigurationSection("item"))
                );
                rewards.put(rewardId, new RewardDefinition(
                    rewardId,
                    section.getString("name", rewardId),
                    section.getString("rarity", "common"),
                    Math.max(0.0D, section.getDouble("chance", 1.0D)),
                    Math.max(0, section.getInt("stock", 1)),
                    StockMode.parse(section.getString("stock-mode", "SERVER_WIDE")),
                    readItem(section.getConfigurationSection("icon")),
                    readItem(section.getConfigurationSection("reward-item")),
                    section.getStringList("commands"),
                    cost
                ));
            }
        }

        MarketLayout layout = new MarketLayout(size, infoSlot, closeSlot, filler, slotRarities);
        return new MarketData(layout, rarities, rewards);
    }

    static void validateCurrent(YamlConfiguration yaml, List<Integer> offerSlots) throws IOException {
        ConfigurationSection rarities = yaml.getConfigurationSection("rarities");
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rarities == null || rewards == null) {
            throw new IOException("market.yml needs rarities and rewards sections");
        }
        ConfigurationSection assignments = yaml.getConfigurationSection("gui.slot-rarities");
        if (yaml.isSet("gui.slot-rarities") && assignments == null) {
            throw new IOException("market.yml:gui.slot-rarities must be a section");
        }
        if (assignments != null && assignments.getKeys(false).size() > offerSlots.size()) {
            throw new IOException("market.yml:gui.slot-rarities has more assignments than GUI offer slots");
        }
        for (String key : assignments == null ? List.<String>of() : assignments.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                throw new IOException("market.yml:gui.slot-rarities." + key + " is not a slot", exception);
            }
            String rarity = assignments.getString(key);
            if (slot < 0 || slot >= 54 || rarity == null || !rarities.isConfigurationSection(rarity)) {
                throw new IOException("market.yml:gui.slot-rarities." + key
                    + " must be a valid slot and an existing rarity");
            }
        }
        for (String id : rarities.getKeys(false)) {
            String path = "rarities." + id;
            if (!rarities.isConfigurationSection(id)) {
                throw new IOException("market.yml:" + path + " must be a section");
            }
            checkText(yaml, path + ".name");
            checkItem(yaml, path + ".icon");
        }
        for (String id : rewards.getKeys(false)) {
            String path = "rewards." + id;
            if (!rewards.isConfigurationSection(id)) {
                throw new IOException("market.yml:" + path + " must be a section");
            }
            checkText(yaml, path + ".name");
            String rarity = yaml.getString(path + ".rarity");
            if (rarity == null || !rarities.isConfigurationSection(rarity)) {
                throw new IOException("market.yml:" + path + ".rarity must name an existing rarity");
            }
            checkNonnegativeNumber(yaml, path + ".chance");
            checkNonnegativeInteger(yaml, path + ".stock");
            if (yaml.isSet(path + ".stock-mode")) {
                String mode = yaml.getString(path + ".stock-mode");
                if (mode == null || !List.of("SERVER_WIDE", "PER_PLAYER").contains(
                    mode.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'))) {
                    throw new IOException("market.yml:" + path + ".stock-mode is invalid");
                }
            }
            Object commands = yaml.get(path + ".commands");
            if (commands != null && (!(commands instanceof List<?> lines)
                || lines.stream().anyMatch(line -> !(line instanceof String)))) {
                throw new IOException("market.yml:" + path + ".commands must be a text list");
            }
            checkItem(yaml, path + ".icon");
            checkItem(yaml, path + ".reward-item");
            String cost = path + ".cost";
            if (yaml.isSet(cost) && !yaml.isConfigurationSection(cost)) {
                throw new IOException("market.yml:" + cost + " must be a section");
            }
            if (yaml.isSet(cost + ".type")) {
                String type = yaml.getString(cost + ".type");
                if (type == null || !List.of("MONEY", "ITEM", "BOTH").contains(type.toUpperCase(Locale.ROOT))) {
                    throw new IOException("market.yml:" + cost + ".type is invalid");
                }
            }
            checkNonnegativeNumber(yaml, cost + ".money");
            checkItem(yaml, cost + ".item");
        }
    }

    private static void checkText(YamlConfiguration yaml, String path) throws IOException {
        if (yaml.isSet(path) && !(yaml.get(path) instanceof String)) {
            throw new IOException("market.yml:" + path + " must be text");
        }
    }

    private static void checkNonnegativeNumber(YamlConfiguration yaml, String path) throws IOException {
        if (yaml.isSet(path) && (!(yaml.get(path) instanceof Number number)
            || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0)) {
            throw new IOException("market.yml:" + path + " must be a finite nonnegative number");
        }
    }

    private static void checkNonnegativeInteger(YamlConfiguration yaml, String path) throws IOException {
        if (yaml.isSet(path) && (!(yaml.get(path) instanceof Integer count) || count < 0)) {
            throw new IOException("market.yml:" + path + " must be a nonnegative integer");
        }
    }

    private static void checkItem(YamlConfiguration yaml, String path) throws IOException {
        if (!yaml.isSet(path)) return;
        ConfigurationSection item = yaml.getConfigurationSection(path);
        if (item == null) throw new IOException("market.yml:" + path + " must be an item section");
        String materialName = item.getString("material");
        if (item.isSet("material") && (materialName == null || Material.matchMaterial(materialName) == null)) {
            throw new IOException("market.yml:" + path + ".material is invalid");
        }
        if (item.isSet("amount") && (!(item.get("amount") instanceof Integer amount) || amount < 1)) {
            throw new IOException("market.yml:" + path + ".amount must be positive");
        }
        if (item.isSet("custom-model-data") && (!(item.get("custom-model-data") instanceof Integer model)
            || model < 0)) {
            throw new IOException("market.yml:" + path + ".custom-model-data must be nonnegative");
        }
        for (String flag : List.of("glow", "glowing")) {
            if (item.isSet(flag) && !(item.get(flag) instanceof Boolean)) {
                throw new IOException("market.yml:" + path + "." + flag + " must be true or false");
            }
        }
        Object flags = item.get("item-flags");
        if (flags != null && (!(flags instanceof List<?> names) || names.stream().anyMatch(name -> {
            if (!(name instanceof String text)) return true;
            try { ItemFlag.valueOf(text.toUpperCase(Locale.ROOT)); return false; }
            catch (IllegalArgumentException exception) { return true; }
        }))) {
            throw new IOException("market.yml:" + path + ".item-flags has an invalid flag");
        }
        checkText(yaml, path + ".name");
        Object lore = item.get("lore");
        if (lore != null && (!(lore instanceof List<?> lines)
            || lines.stream().anyMatch(line -> !(line instanceof String)))) {
            throw new IOException("market.yml:" + path + ".lore must be a text list");
        }
        if (item.isSet("skull")) {
            ConfigurationSection skull = item.getConfigurationSection("skull");
            if (skull == null) throw new IOException("market.yml:" + path + ".skull must be a section");
            checkText(yaml, path + ".skull.owner-name");
            checkText(yaml, path + ".skull.owner-uuid");
            if (skull.isSet("owner-uuid")) {
                try { UUID.fromString(skull.getString("owner-uuid")); }
                catch (IllegalArgumentException exception) {
                    throw new IOException("market.yml:" + path + ".skull.owner-uuid is invalid", exception);
                }
            }
            if (skull.isSet("textures")) {
                ConfigurationSection textures = skull.getConfigurationSection("textures");
                if (textures == null) {
                    throw new IOException("market.yml:" + path + ".skull.textures must be a section");
                }
                checkTextureUrl(textures, "skin", path);
                checkTextureUrl(textures, "cape", path);
                if (textures.isSet("model")) {
                    String model = textures.getString("model");
                    if (model == null || !List.of("CLASSIC", "SLIM").contains(model.toUpperCase(Locale.ROOT))) {
                        throw new IOException("market.yml:" + path + ".skull.textures.model is invalid");
                    }
                }
            }
        }
    }

    private static void checkTextureUrl(ConfigurationSection textures, String key, String itemPath)
        throws IOException {
        if (!textures.isSet(key)) return;
        String value = textures.getString(key);
        try {
            URI uri = value == null ? null : new URI(value);
            if (uri == null || uri.getScheme() == null
                || !List.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getPath() == null || uri.getPath().isBlank()) {
                throw new IOException("market.yml:" + itemPath + ".skull.textures." + key + " is not a URL");
            }
        } catch (URISyntaxException exception) {
            throw new IOException("market.yml:" + itemPath + ".skull.textures." + key + " is not a URL", exception);
        }
    }

    public void save(MarketData marketData) {
        save(marketData, null);
    }

    /** Stock changes must never serialize the cached market over owner edits made on disk. */
    public void saveState(MarketSnapshot snapshot) {
        saveState(file, snapshot);
    }

    static void saveState(File file, MarketSnapshot snapshot) {
        try {
            YamlConfiguration yaml = loadForUpdate(file);
            yaml.set("state", null);
            writeState(yaml, snapshot);
            writeAtomically(file, yaml);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not save market state without altering owner data", exception);
        }
    }

    private static YamlConfiguration loadForUpdate(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        return yaml;
    }

    private static void writeAtomically(File file, YamlConfiguration yaml) throws IOException {
        File temporary = File.createTempFile("market-write-", ".yml", file.getParentFile());
        try {
            Files.writeString(temporary.toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }

    public MarketState loadState() {
        return readState(YamlConfiguration.loadConfiguration(file));
    }

    private MarketState readState(YamlConfiguration yaml) {
        ConfigurationSection stateSection = yaml.getConfigurationSection("state");
        if (stateSection == null) {
            return null;
        }

        long restockedAt = stateSection.getLong("restocked-at", 0L);
        long nextRestockAt = stateSection.getLong("next-restock-at", 0L);
        if (restockedAt <= 0L || nextRestockAt <= 0L) {
            return null;
        }

        Map<String, MarketState.OfferState> offers = new LinkedHashMap<>();
        ConfigurationSection offersSection = stateSection.getConfigurationSection("offers");
        if (offersSection != null) {
            for (String rewardId : offersSection.getKeys(false)) {
                ConfigurationSection offerSection = offersSection.getConfigurationSection(rewardId);
                if (offerSection == null) {
                    continue;
                }
                Map<UUID, Integer> perPlayerStock = new LinkedHashMap<>();
                ConfigurationSection perPlayerSection = offerSection.getConfigurationSection("per-player-remaining-stock");
                if (perPlayerSection != null) {
                    for (String rawUuid : perPlayerSection.getKeys(false)) {
                        UUID uuid = parseUuid(rawUuid);
                        if (uuid != null) {
                            perPlayerStock.put(uuid, Math.max(0, perPlayerSection.getInt(rawUuid, 0)));
                        }
                    }
                }
                offers.put(rewardId, new MarketState.OfferState(
                    rewardId,
                    Math.max(0, offerSection.getInt("remaining-stock", 0)),
                    perPlayerStock
                ));
            }
        }

        Map<Integer, String> slotRewardIds = new LinkedHashMap<>();
        ConfigurationSection slotsSection = stateSection.getConfigurationSection("slots");
        if (slotsSection != null) {
            for (String key : slotsSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    String rewardId = slotsSection.getString(key, "");
                    if (!rewardId.isBlank()) {
                        slotRewardIds.put(slot, rewardId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (offers.isEmpty()) {
            return null;
        }
        return new MarketState(restockedAt, nextRestockAt, offers, slotRewardIds);
    }

    public void save(MarketData marketData, MarketSnapshot snapshot) {
        YamlConfiguration yaml;
        try {
            yaml = loadForUpdate(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not read market.yml before editor save", exception);
        }

        MarketLayout layout = marketData.getLayout();
        yaml.set("gui.slot-rarities", null);
        yaml.set("rarities", null);
        yaml.set("rewards", null);
        yaml.createSection("gui.slot-rarities");
        yaml.createSection("rarities");
        yaml.createSection("rewards");
        for (Map.Entry<Integer, String> entry : layout.getSlotRarities().entrySet()) {
            yaml.set("gui.slot-rarities." + entry.getKey(), entry.getValue());
        }

        for (RarityDefinition rarity : marketData.getRarities().values()) {
            String basePath = "rarities." + rarity.getId();
            yaml.set(basePath + ".name", rarity.getDisplayName());
            writeItem(yaml.createSection(basePath + ".icon"), rarity.getIcon());
        }

        for (RewardDefinition reward : marketData.getRewards().values()) {
            String basePath = "rewards." + reward.getId();
            yaml.set(basePath + ".name", reward.getDisplayName());
            yaml.set(basePath + ".rarity", reward.getRarityId());
            yaml.set(basePath + ".chance", reward.getChance());
            yaml.set(basePath + ".stock", reward.getStock());
            yaml.set(basePath + ".stock-mode", reward.getStockMode().name());
            writeItem(yaml.createSection(basePath + ".icon"), reward.getIcon());
            writeItem(yaml.createSection(basePath + ".reward-item"), reward.getRewardItem());
            yaml.set(basePath + ".commands", reward.getCommands());
            yaml.set(basePath + ".cost.type", reward.getCost().getType().name());
            yaml.set(basePath + ".cost.money", reward.getCost().getMoneyCost());
            writeItem(yaml.createSection(basePath + ".cost.item"), reward.getCost().getItemCost());
        }

        if (snapshot != null) {
            yaml.set("state", null);
            writeState(yaml, snapshot);
        }

        try {
            writeAtomically(file, yaml);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save market.yml", exception);
        }
    }

    public File getFile() {
        return file;
    }

    private static void writeState(YamlConfiguration yaml, MarketSnapshot snapshot) {
        yaml.set("state.restocked-at", snapshot.getRestockedAt());
        yaml.set("state.next-restock-at", snapshot.getNextRestockAt());
        for (ActiveOffer offer : snapshot.getOffersByRewardId().values()) {
            String basePath = "state.offers." + offer.getRewardId();
            yaml.set(basePath + ".remaining-stock", offer.getRemainingStock());
            for (Map.Entry<UUID, Integer> entry : offer.getPerPlayerRemainingStock().entrySet()) {
                yaml.set(basePath + ".per-player-remaining-stock." + entry.getKey(), Math.max(0, entry.getValue()));
            }
        }
        for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
            yaml.set("state.slots." + entry.getKey(), entry.getValue().getRewardId());
        }
    }

    public static ItemStack createNamedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FoText.color(name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(FoText.color(line));
            }
            meta.setLore(coloredLore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private CostType parseCostType(String raw) {
        try {
            return CostType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return CostType.MONEY;
        }
    }

    private int clampSize(int size) {
        int clamped = Math.max(9, Math.min(54, size));
        return clamped - (clamped % 9);
    }

    public ItemStack readItem(ConfigurationSection section) {
        if (section == null || section.getKeys(false).isEmpty()) {
            return null;
        }

        ItemStack item = readSerializedItemBytes(section);
        if (item == null) item = readSerializedItem(section);
        Material material = Material.matchMaterial(section.getString("material",
            item == null ? "STONE" : item.getType().name()));
        if (material == null) {
            material = Material.STONE;
        }
        int amount = Math.max(1, section.getInt("amount", item == null ? 1 : item.getAmount()));
        if (item == null) {
            item = new ItemStack(material, amount);
        } else {
            if (section.isSet("material") && item.getType() != material) item.setType(material);
            item.setAmount(amount);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (section.contains("name")) {
                meta.setDisplayName(FoText.color(section.getString("name", "")));
            }
            if (section.contains("lore")) {
                List<String> lore = new ArrayList<>();
                for (String line : section.getStringList("lore")) {
                    lore.add(FoText.color(line));
                }
                meta.setLore(lore);
            }
            if (section.isSet("glowing") || section.isSet("glow")) {
                boolean glowing = section.getBoolean("glowing", section.getBoolean("glow", false));
                meta.setEnchantmentGlintOverride(glowing);
            }
            if (section.getBoolean("glowing", section.getBoolean("glow", false))) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            if (section.isInt("custom-model-data")) {
                meta.setCustomModelData(section.getInt("custom-model-data"));
            }
            readItemFlags(section, meta);
            if (meta instanceof EnchantmentStorageMeta storageMeta) {
                for (Map.Entry<Enchantment, Integer> entry : readEnchantments(section, "stored-enchantments").entrySet()) {
                    storageMeta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
                }
            }
            if (meta instanceof SkullMeta skullMeta) {
                readSkullMeta(section, skullMeta);
            }
            item.setItemMeta(meta);
        }
        for (Map.Entry<Enchantment, Integer> entry : readEnchantments(section, "enchantments").entrySet()) {
            item.addUnsafeEnchantment(entry.getKey(), entry.getValue());
        }
        return item;
    }

    private void writeItem(ConfigurationSection section, ItemStack item) {
        if (section == null || item == null || item.getType().isAir()) {
            return;
        }

        String serializedBytes = serializeItemBytes(item);
        if (serializedBytes != null) {
            section.set(SERIALIZED_ITEM_BYTES_PATH, serializedBytes);
        }
        section.set(SERIALIZED_ITEM_PATH, item.clone());
        section.set("material", item.getType().name());
        section.set("amount", item.getAmount());

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        if (meta.hasDisplayName()) {
            section.set("name", meta.getDisplayName());
        }
        if (meta.hasLore()) {
            section.set("lore", meta.getLore());
        }
        if (meta.hasCustomModelData()) {
            section.set("custom-model-data", meta.getCustomModelData());
        }
        if (meta.hasEnchantmentGlintOverride()) {
            section.set("glowing", meta.getEnchantmentGlintOverride());
        }
        if (!meta.getItemFlags().isEmpty()) {
            section.set("item-flags", meta.getItemFlags().stream()
                .map(ItemFlag::name)
                .sorted()
                .toList());
        }
        writeEnchantments(section, "enchantments", meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            writeEnchantments(section, "stored-enchantments", storageMeta.getStoredEnchants());
        }
        if (meta instanceof SkullMeta skullMeta) {
            writeSkullMeta(section, skullMeta);
        }
    }

    private ItemStack readSerializedItem(ConfigurationSection section) {
        if (!section.contains(SERIALIZED_ITEM_PATH)) {
            return null;
        }

        try {
            ItemStack item = section.getItemStack(SERIALIZED_ITEM_PATH);
            if (item == null || item.getType().isAir()) {
                return null;
            }
            return item.clone();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not load full item data at " + section.getCurrentPath() + "; falling back to legacy item fields.");
            return null;
        }
    }

    private ItemStack readSerializedItemBytes(ConfigurationSection section) {
        String encoded = section.getString(SERIALIZED_ITEM_BYTES_PATH);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        ItemStack item = FoItemStacks.decode(encoded).orElse(null);
        if (item == null || item.getType().isAir()) {
            plugin.getLogger().warning("Could not load full item bytes at " + section.getCurrentPath() + "; falling back to YAML item data.");
            return null;
        }
        return item.clone();
    }

    private String serializeItemBytes(ItemStack item) {
        try {
            return FoItemStacks.encode(item);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not save full item bytes; falling back to YAML item data.");
        }
        return null;
    }

    private void readSkullMeta(ConfigurationSection itemSection, SkullMeta skullMeta) {
        ConfigurationSection skullSection = itemSection.getConfigurationSection("skull");
        if (skullSection == null) {
            return;
        }

        Object rawProfile = skullSection.get("owner-profile");
        if (rawProfile instanceof PlayerProfile profile) {
            setOwnerProfile(skullMeta, profile);
        } else {
            PlayerProfile profile = createPlayerProfile(skullSection);
            if (profile != null) {
                setOwnerProfile(skullMeta, profile);
            }
        }

        String noteBlockSound = skullSection.getString("note-block-sound");
        if (noteBlockSound != null && !noteBlockSound.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(noteBlockSound);
            if (key != null) {
                skullMeta.setNoteBlockSound(key);
            }
        }
    }

    private void writeSkullMeta(ConfigurationSection itemSection, SkullMeta skullMeta) {
        PlayerProfile profile = skullMeta.getOwnerProfile();
        NamespacedKey noteBlockSound = skullMeta.getNoteBlockSound();
        if (profile == null && !skullMeta.hasOwner() && noteBlockSound == null) {
            return;
        }

        ConfigurationSection skullSection = itemSection.createSection("skull");
        if (profile != null) {
            skullSection.set("owner-profile", profile);
            if (profile.getUniqueId() != null) {
                skullSection.set("owner-uuid", profile.getUniqueId().toString());
            }
            if (profile.getName() != null && !profile.getName().isBlank()) {
                skullSection.set("owner-name", profile.getName());
            }
            writeSkullTextures(skullSection, profile.getTextures());
        } else if (skullMeta.hasOwner()) {
            skullSection.set("owner-name", skullMeta.getOwner());
        }

        if (noteBlockSound != null) {
            skullSection.set("note-block-sound", noteBlockSound.toString());
        }
    }

    private PlayerProfile createPlayerProfile(ConfigurationSection skullSection) {
        UUID uniqueId = parseUuid(skullSection.getString("owner-uuid"));
        String ownerName = blankToNull(skullSection.getString("owner-name", skullSection.getString("owner")));
        ConfigurationSection texturesSection = skullSection.getConfigurationSection("textures");
        URL skin = texturesSection == null ? null : parseUrl(texturesSection.getString("skin"));
        if (uniqueId == null && ownerName == null && skin == null) {
            return null;
        }

        if (uniqueId == null && ownerName == null) {
            uniqueId = UUID.nameUUIDFromBytes(("FoBlackmarket:" + skin).getBytes(StandardCharsets.UTF_8));
        }

        PlayerProfile profile;
        if (uniqueId != null && ownerName != null) {
            profile = Bukkit.createPlayerProfile(uniqueId, ownerName);
        } else if (uniqueId != null) {
            profile = Bukkit.createPlayerProfile(uniqueId);
        } else {
            profile = Bukkit.createPlayerProfile(ownerName);
        }

        readSkullTextures(texturesSection, profile, skin);
        return profile;
    }

    private void readSkullTextures(ConfigurationSection texturesSection, PlayerProfile profile, URL skin) {
        if (texturesSection == null) {
            return;
        }

        PlayerTextures textures = profile.getTextures();
        if (skin != null) {
            PlayerTextures.SkinModel model = parseSkinModel(texturesSection.getString("model"));
            if (model == null) {
                textures.setSkin(skin);
            } else {
                textures.setSkin(skin, model);
            }
        }

        URL cape = parseUrl(texturesSection.getString("cape"));
        if (cape != null) {
            textures.setCape(cape);
        }
        profile.setTextures(textures);
    }

    private void writeSkullTextures(ConfigurationSection skullSection, PlayerTextures textures) {
        if (textures == null || textures.isEmpty()) {
            return;
        }

        URL skin = textures.getSkin();
        URL cape = textures.getCape();
        if (skin == null && cape == null) {
            return;
        }

        ConfigurationSection texturesSection = skullSection.createSection("textures");
        if (skin != null) {
            texturesSection.set("skin", skin.toString());
            PlayerTextures.SkinModel model = textures.getSkinModel();
            if (model != null) {
                texturesSection.set("model", model.name());
            }
        }
        if (cape != null) {
            texturesSection.set("cape", cape.toString());
        }
    }

    private boolean setOwnerProfile(SkullMeta skullMeta, PlayerProfile profile) {
        try {
            skullMeta.setOwnerProfile(profile);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private UUID parseUuid(String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private URL parseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            return new URI(rawUrl).toURL();
        } catch (IllegalArgumentException | MalformedURLException | URISyntaxException exception) {
            return null;
        }
    }

    private PlayerTextures.SkinModel parseSkinModel(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return null;
        }
        try {
            return PlayerTextures.SkinModel.valueOf(rawModel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void readItemFlags(ConfigurationSection section, ItemMeta meta) {
        for (String rawFlag : section.getStringList("item-flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(rawFlag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private Map<Enchantment, Integer> readEnchantments(ConfigurationSection section, String path) {
        Map<Enchantment, Integer> enchantments = new LinkedHashMap<>();
        if (section == null) {
            return enchantments;
        }

        for (Map<?, ?> serializedEnchantment : section.getMapList(path)) {
            Object rawKey = serializedEnchantment.get("key");
            if (rawKey == null) {
                continue;
            }
            Enchantment enchantment = parseEnchantment(String.valueOf(rawKey));
            if (enchantment == null) {
                continue;
            }
            enchantments.put(enchantment, parseEnchantmentLevel(serializedEnchantment.get("level")));
        }

        ConfigurationSection legacySection = section.getConfigurationSection(path);
        if (legacySection == null) {
            return enchantments;
        }
        for (String rawKey : legacySection.getKeys(false)) {
            Enchantment enchantment = parseEnchantment(rawKey);
            if (enchantment == null) {
                continue;
            }
            enchantments.put(enchantment, Math.max(1, legacySection.getInt(rawKey, 1)));
        }
        return enchantments;
    }

    private Enchantment parseEnchantment(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }

        String normalized = rawKey.toLowerCase(Locale.ROOT);
        NamespacedKey namespacedKey = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (namespacedKey != null) {
            Enchantment enchantment = Enchantment.getByKey(namespacedKey);
            if (enchantment != null) {
                return enchantment;
            }
        }
        return Enchantment.getByName(rawKey.toUpperCase(Locale.ROOT));
    }

    private void writeEnchantments(ConfigurationSection section, String path, Map<Enchantment, Integer> enchantments) {
        if (enchantments.isEmpty()) {
            return;
        }

        List<Map<String, Object>> serializedEnchantments = new ArrayList<>();
        List<Map.Entry<Enchantment, Integer>> sortedEnchantments = enchantments.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getKey().toString()))
            .toList();
        for (Map.Entry<Enchantment, Integer> entry : sortedEnchantments) {
            Map<String, Object> serializedEnchantment = new LinkedHashMap<>();
            serializedEnchantment.put("key", entry.getKey().getKey().toString());
            serializedEnchantment.put("level", entry.getValue());
            serializedEnchantments.add(serializedEnchantment);
        }
        section.set(path, serializedEnchantments);
    }

    private int parseEnchantmentLevel(Object rawLevel) {
        if (rawLevel instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (rawLevel == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(rawLevel)));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }
}
