package me.foesio.foBlackmarket.market;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.foesio.core.command.CommandPlaceholders;
import me.foesio.core.inventory.InventoryDepositResult;
import me.foesio.core.inventory.OverflowPolicy;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.config.ConfigManager;
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.hook.VaultHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class MarketService {

    private final FoBlackmarket plugin;
    private final ConfigManager configManager;
    private final MarketRepository marketRepository;
    private final VaultHook vaultHook;
    private final GuiManager guiManager;
    private final Object lock = new Object();

    private MarketData marketData;
    private MarketSnapshot snapshot;
    private long nextRestockAt;
    private int restockTaskId = -1;

    public MarketService(
        FoBlackmarket plugin,
        ConfigManager configManager,
        MarketRepository marketRepository,
        VaultHook vaultHook,
        GuiManager guiManager
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.marketRepository = marketRepository;
        this.vaultHook = vaultHook;
        this.guiManager = guiManager;
    }

    public void start() {
        reloadFromDisk();
    }

    public void shutdown() {
        synchronized (lock) {
            persistCurrentState();
        }
        if (restockTaskId != -1) {
            Bukkit.getScheduler().cancelTask(restockTaskId);
            restockTaskId = -1;
        }
    }

    public void reloadFromDisk() {
        boolean restored;
        synchronized (lock) {
            vaultHook.hook();
            this.marketData = marketRepository.load();
            this.snapshot = restoreSnapshot(marketData, marketRepository.loadState());
            restored = snapshot != null;
            if (restored) {
                long now = System.currentTimeMillis();
                this.nextRestockAt = snapshot.getNextRestockAt();
                if (nextRestockAt <= now) {
                    this.nextRestockAt = now + restockIntervalMillis();
                    this.snapshot = withNextRestock(snapshot, nextRestockAt);
                    persistCurrentState();
                }
                scheduleRestocks();
            }
        }
        if (!restored) {
            restockNow(false);
            return;
        }
        guiManager.refreshPlayerMarketGuis();
    }

    public void persistMarketData(boolean broadcast) {
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
        }
        restockNow(broadcast);
    }

    public void persistMarketDataWithoutRestock() {
        boolean refreshAllGuis;
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
            refreshAllGuis = refreshActiveOffersFromMarketData();
            persistMarketDataWithCurrentState();
        }

        if (refreshAllGuis) {
            guiManager.refreshPlayerMarketGuis();
        }
    }

    public void persistRewardChange(String previousRewardId, String currentRewardId) {
        ActiveOffer refreshedOffer = null;
        boolean refreshAllGuis = false;
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
            RewardRefreshResult result = refreshActiveReward(previousRewardId, currentRewardId);
            refreshedOffer = result.offer();
            refreshAllGuis = result.refreshAllGuis();
            persistMarketDataWithCurrentState();
        }

        if (refreshAllGuis) {
            guiManager.refreshPlayerMarketGuis();
        } else if (refreshedOffer != null) {
            guiManager.refreshPlayerMarketOffer(refreshedOffer);
        }
    }

    public void persistRewardRemoval(String rewardId) {
        boolean refreshAllGuis;
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
            refreshAllGuis = removeActiveReward(rewardId);
            persistMarketDataWithCurrentState();
        }

        if (refreshAllGuis) {
            guiManager.refreshPlayerMarketGuis();
        }
    }

    public void rescheduleRestockFromNow() {
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
            this.nextRestockAt = System.currentTimeMillis() + restockIntervalMillis();
            if (snapshot != null) {
                this.snapshot = withNextRestock(snapshot, nextRestockAt);
                persistCurrentState();
                scheduleRestocks();
            } else {
                marketRepository.save(marketData);
            }
        }
        guiManager.refreshPlayerMarketGuis();
    }

    public void restockNow(boolean broadcast) {
        synchronized (lock) {
            if (marketData == null) {
                marketData = marketRepository.load();
            }
            long now = System.currentTimeMillis();
            this.nextRestockAt = now + restockIntervalMillis();
            this.snapshot = buildSnapshot(marketData, now, nextRestockAt);
            persistCurrentState();
            scheduleRestocks();
        }

        guiManager.refreshPlayerMarketGuis();
        if (broadcast) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getSounds().play(player, "market.restock");
            }
        }
        if (broadcast && configManager.isAnnounceRestocks()) {
            Bukkit.broadcastMessage(plugin.getMessages().render("messages.market-restocked-broadcast", "", Map.of(
                "time_remaining", configManager.formatDuration(getTimeUntilRestock())
            )));
        }
    }

    public MarketData getMarketData() {
        synchronized (lock) {
            return marketData;
        }
    }

    public MarketSnapshot getSnapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    public List<RarityDefinition> getRarities() {
        synchronized (lock) {
            if (marketData == null) {
                return List.of();
            }
            return new ArrayList<>(marketData.getRarities().values());
        }
    }

    public ActiveOffer getOffer(String rewardId) {
        synchronized (lock) {
            if (snapshot == null) {
                return null;
            }
            return snapshot.getOffersByRewardId().get(rewardId);
        }
    }

    public ActiveOffer getOffer(int slot) {
        synchronized (lock) {
            if (snapshot == null) {
                return null;
            }
            return snapshot.getOffersBySlot().get(slot);
        }
    }

    public List<ActiveOffer> getOffersForRarity(String rarityId) {
        synchronized (lock) {
            if (snapshot == null || rarityId == null || rarityId.isBlank()) {
                return List.of();
            }
            return snapshot.getOffersByRewardId().values().stream()
                .filter(offer -> Objects.equals(offer.getRarity().getId(), rarityId))
                .sorted(Comparator
                    .comparingDouble((ActiveOffer offer) -> offer.getReward().getChance()).reversed()
                    .thenComparing(offer -> offer.getReward().getId()))
                .toList();
        }
    }

    public String getDefaultRarityId() {
        synchronized (lock) {
            if (marketData == null || marketData.getRarities().isEmpty()) {
                return null;
            }
            Collection<ActiveOffer> offers = snapshot == null ? List.of() : snapshot.getOffersByRewardId().values();
            for (RarityDefinition rarity : marketData.getRarities().values()) {
                for (ActiveOffer offer : offers) {
                    if (Objects.equals(offer.getRarity().getId(), rarity.getId())) {
                        return rarity.getId();
                    }
                }
            }
            return marketData.getRarities().values().iterator().next().getId();
        }
    }

    public Duration getTimeUntilRestock() {
        return Duration.ofMillis(Math.max(0L, nextRestockAt - System.currentTimeMillis()));
    }

    public long getNextRestockAt() {
        return nextRestockAt;
    }

    public boolean hasActiveMarket() {
        synchronized (lock) {
            return snapshot != null;
        }
    }

    public PurchaseResult purchase(Player player, String rewardId) {
        PurchaseResult result;
        ActiveOffer updatedOffer = null;
        synchronized (lock) {
            if (snapshot == null) {
                return PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
            }
            ActiveOffer offer = snapshot.getOffersByRewardId().get(rewardId);
            if (offer == null) {
                return PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
            }
            int remainingStock = offer.getRemainingStock(player.getUniqueId());
            if (remainingStock <= 0) {
                return PurchaseResult.of(PurchaseResult.Status.SOLD_OUT);
            }

            RewardDefinition reward = offer.getReward();
            CostDefinition cost = reward.getCost();
            ItemStack rewardItem = reward.getRewardItem();
            boolean hasRewardItem = rewardItem != null && !rewardItem.getType().isAir();
            boolean hasCommands = !reward.getCommands().isEmpty();
            if (!hasRewardItem && !hasCommands) {
                return PurchaseResult.of(PurchaseResult.Status.FAILED);
            }

            if (hasRewardItem && !plugin.getCore().inventoryDeposits().canFitAll(player, rewardItem)) {
                return PurchaseResult.of(PurchaseResult.Status.INVENTORY_FULL);
            }

            if (requiresMoney(cost)) {
                double moneyCost = Math.max(0.0D, cost.getMoneyCost());
                if (!vaultHook.hasEconomy() || !vaultHook.has(player, moneyCost)) {
                    return PurchaseResult.moneyFailure(vaultHook.format(moneyCost));
                }
            }

            if (requiresItems(cost)) {
                ItemStack costItem = cost.getItemCost();
                if (costItem == null || costItem.getType().isAir() || !hasRequiredItems(player.getInventory(), costItem)) {
                    return PurchaseResult.of(PurchaseResult.Status.NOT_ENOUGH_ITEMS);
                }
            }

            if (requiresMoney(cost)) {
                double moneyCost = Math.max(0.0D, cost.getMoneyCost());
                if (!vaultHook.withdraw(player, moneyCost)) {
                    return PurchaseResult.of(PurchaseResult.Status.FAILED);
                }
            }

            if (requiresItems(cost)) {
                removeRequiredItems(player.getInventory(), cost.getItemCost());
            }

            if (hasRewardItem) {
                InventoryDepositResult deposit = plugin.getCore().inventoryDeposits()
                    .deposit(player, rewardItem.clone(), OverflowPolicy.BLOCK_COLLECTION);
                if (!deposit.fullyAccepted()) {
                    return PurchaseResult.of(PurchaseResult.Status.INVENTORY_FULL);
                }
            }

            for (String command : reward.getCommands()) {
                String resolved = CommandPlaceholders.apply(command, Map.of(
                    "player", player.getName(),
                    "player_uuid", player.getUniqueId().toString(),
                    "uuid", player.getUniqueId().toString()
                ));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            }

            remainingStock = offer.decrementRemainingStock(player.getUniqueId());
            cacheOfferDisplay(offer);
            persistCurrentState();
            updatedOffer = offer;
            result = PurchaseResult.success(reward.getDisplayName(), remainingStock);
        }
        guiManager.refreshPlayerMarketOffer(updatedOffer);
        return result;
    }

    public PurchaseResult purchase(Player player, int slot) {
        ActiveOffer offer = getOffer(slot);
        if (offer == null) {
            return PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
        }
        return purchase(player, offer.getRewardId());
    }

    public String describeCost(RewardDefinition reward) {
        CostDefinition cost = reward.getCost();
        if (cost.getType() == CostType.MONEY) {
            return vaultHook.format(cost.getMoneyCost());
        }
        if (cost.getType() == CostType.ITEM) {
            return describeItemCost(cost.getItemCost());
        }
        return vaultHook.format(cost.getMoneyCost()) + " + " + describeItemCost(cost.getItemCost());
    }

    public ItemStack createOfferDisplayItem(ActiveOffer offer, Player viewer) {
        if (offer == null) {
            return null;
        }
        synchronized (lock) {
            return buildOfferDisplayItem(offer, viewer);
        }
    }

    private void scheduleRestocks() {
        if (restockTaskId != -1) {
            Bukkit.getScheduler().cancelTask(restockTaskId);
        }
        restockTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
            plugin,
            () -> restockNow(true),
            delayTicksUntilNextRestock()
        );
    }

    private void persistCurrentState() {
        if (marketData != null && snapshot != null) {
            marketRepository.save(marketData, snapshot);
        }
    }

    private void persistMarketDataWithCurrentState() {
        if (marketData == null) {
            return;
        }
        if (snapshot == null) {
            marketRepository.save(marketData);
            return;
        }
        marketRepository.save(marketData, snapshot);
    }

    private RewardRefreshResult refreshActiveReward(String previousRewardId, String currentRewardId) {
        if (snapshot == null || currentRewardId == null || currentRewardId.isBlank()) {
            return RewardRefreshResult.none();
        }

        ActiveOffer source = activeOffer(previousRewardId);
        if (source == null) {
            source = activeOffer(currentRewardId);
        }
        if (source == null) {
            return RewardRefreshResult.none();
        }

        RewardDefinition reward = marketData.getRewards().get(currentRewardId);
        RarityDefinition rarity = reward == null ? null : marketData.getRarities().get(reward.getRarityId());
        if (reward == null || rarity == null) {
            removeActiveOffer(source, previousRewardId);
            return RewardRefreshResult.fullRefresh();
        }

        ActiveOffer replacement = updatedActiveOffer(source, reward, rarity);
        Map<String, ActiveOffer> offersByRewardId = new LinkedHashMap<>(snapshot.getOffersByRewardId());
        offersByRewardId.remove(source.getRewardId());
        if (previousRewardId != null) {
            offersByRewardId.remove(previousRewardId);
        }
        offersByRewardId.remove(currentRewardId);
        offersByRewardId.put(reward.getId(), replacement);

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
            ActiveOffer offer = entry.getValue();
            if (offer == source || Objects.equals(offer.getRewardId(), source.getRewardId()) || Objects.equals(offer.getRewardId(), previousRewardId)) {
                offersBySlot.put(entry.getKey(), replacement);
            } else {
                offersBySlot.put(entry.getKey(), offer);
            }
        }

        snapshot = new MarketSnapshot(offersByRewardId, offersBySlot, snapshot.getRestockedAt(), snapshot.getNextRestockAt());
        return new RewardRefreshResult(replacement, false);
    }

    private boolean refreshActiveOffersFromMarketData() {
        if (snapshot == null) {
            return false;
        }

        Map<String, ActiveOffer> offersByRewardId = new LinkedHashMap<>();
        Map<ActiveOffer, ActiveOffer> replacements = new IdentityHashMap<>();
        for (ActiveOffer source : snapshot.getOffersByRewardId().values()) {
            RewardDefinition reward = marketData.getRewards().get(source.getRewardId());
            RarityDefinition rarity = reward == null ? null : marketData.getRarities().get(reward.getRarityId());
            if (reward == null || rarity == null) {
                continue;
            }
            ActiveOffer replacement = updatedActiveOffer(source, reward, rarity);
            offersByRewardId.put(reward.getId(), replacement);
            replacements.put(source, replacement);
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
            ActiveOffer replacement = replacements.get(entry.getValue());
            if (replacement != null) {
                offersBySlot.put(entry.getKey(), replacement);
            }
        }

        snapshot = new MarketSnapshot(offersByRewardId, offersBySlot, snapshot.getRestockedAt(), snapshot.getNextRestockAt());
        return true;
    }

    private ActiveOffer activeOffer(String rewardId) {
        if (snapshot == null || rewardId == null || rewardId.isBlank()) {
            return null;
        }
        return snapshot.getOffersByRewardId().get(rewardId);
    }

    private ActiveOffer updatedActiveOffer(ActiveOffer source, RewardDefinition reward, RarityDefinition rarity) {
        int maxStock = Math.max(0, reward.getStock());
        ActiveOffer replacement = new ActiveOffer(reward.getId(), reward, rarity, Math.min(source.getRemainingStock(), maxStock));
        replacement.setPerPlayerRemainingStock(cappedPerPlayerStock(source.getPerPlayerRemainingStock(), maxStock));
        cacheOfferDisplay(replacement);
        return replacement;
    }

    private Map<UUID, Integer> cappedPerPlayerStock(Map<UUID, Integer> stock, int maxStock) {
        Map<UUID, Integer> cappedStock = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : stock.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cappedStock.put(entry.getKey(), Math.min(Math.max(0, entry.getValue()), maxStock));
            }
        }
        return cappedStock;
    }

    private boolean removeActiveReward(String rewardId) {
        ActiveOffer source = activeOffer(rewardId);
        return source != null && removeActiveOffer(source, rewardId);
    }

    private boolean removeActiveOffer(ActiveOffer source, String rewardId) {
        if (snapshot == null || source == null) {
            return false;
        }

        Map<String, ActiveOffer> offersByRewardId = new LinkedHashMap<>(snapshot.getOffersByRewardId());
        offersByRewardId.remove(source.getRewardId());
        if (rewardId != null) {
            offersByRewardId.remove(rewardId);
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
            ActiveOffer offer = entry.getValue();
            if (offer == source || Objects.equals(offer.getRewardId(), source.getRewardId()) || Objects.equals(offer.getRewardId(), rewardId)) {
                continue;
            }
            offersBySlot.put(entry.getKey(), offer);
        }

        snapshot = new MarketSnapshot(offersByRewardId, offersBySlot, snapshot.getRestockedAt(), snapshot.getNextRestockAt());
        return true;
    }

    private long restockIntervalMillis() {
        return configManager.getRestockIntervalMinutes() * 60_000L;
    }

    private long delayTicksUntilNextRestock() {
        long delayMillis = Math.max(50L, nextRestockAt - System.currentTimeMillis());
        return Math.max(1L, (delayMillis + 49L) / 50L);
    }

    private MarketSnapshot withNextRestock(MarketSnapshot source, long nextRestock) {
        return new MarketSnapshot(
            source.getOffersByRewardId(),
            source.getOffersBySlot(),
            source.getRestockedAt(),
            nextRestock
        );
    }

    private MarketSnapshot restoreSnapshot(MarketData data, MarketState state) {
        if (data == null || state == null || state.offers().isEmpty()) {
            return null;
        }

        Map<String, ActiveOffer> offersByRewardId = new LinkedHashMap<>();
        for (MarketState.OfferState offerState : state.offers().values()) {
            RewardDefinition reward = data.getRewards().get(offerState.rewardId());
            if (reward == null) {
                continue;
            }
            RarityDefinition rarity = data.getRarities().get(reward.getRarityId());
            if (rarity == null) {
                continue;
            }
            ActiveOffer offer = new ActiveOffer(reward.getId(), reward, rarity, offerState.remainingStock());
            offer.setPerPlayerRemainingStock(offerState.perPlayerRemainingStock());
            cacheOfferDisplay(offer);
            offersByRewardId.put(reward.getId(), offer);
        }

        if (offersByRewardId.isEmpty()) {
            return null;
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : state.slotRewardIds().entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= data.getLayout().getSize() || slot == data.getLayout().getInfoSlot() || slot == data.getLayout().getCloseSlot()) {
                continue;
            }
            ActiveOffer offer = offersByRewardId.get(entry.getValue());
            if (offer != null) {
                offersBySlot.put(slot, offer);
            }
        }

        if (offersBySlot.isEmpty()) {
            return null;
        }

        return new MarketSnapshot(offersByRewardId, offersBySlot, state.restockedAt(), state.nextRestockAt());
    }

    private MarketSnapshot buildSnapshot(MarketData data, long restockedAt, long nextRestock) {
        Map<String, ActiveOffer> offersByRewardId = new LinkedHashMap<>();
        List<RewardDefinition> sortedRewards = new ArrayList<>(data.getRewards().values());
        sortedRewards.sort(Comparator.comparing(RewardDefinition::getId));
        for (RewardDefinition reward : sortedRewards) {
            RarityDefinition rarity = data.getRarities().get(reward.getRarityId());
            if (rarity == null) {
                continue;
            }
            ActiveOffer offer = new ActiveOffer(reward.getId(), reward, rarity, Math.max(0, reward.getStock()));
            cacheOfferDisplay(offer);
            offersByRewardId.put(reward.getId(), offer);
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        List<Map.Entry<Integer, String>> sortedSlots = new ArrayList<>(data.getLayout().getSlotRarities().entrySet());
        sortedSlots.sort(Comparator.comparingInt(Map.Entry::getKey));
        for (Map.Entry<Integer, String> entry : sortedSlots) {
            ActiveOffer offer = pickOfferForRarity(entry.getValue(), offersByRewardId);
            if (offer != null) {
                offersBySlot.put(entry.getKey(), offer);
            }
        }

        return new MarketSnapshot(offersByRewardId, offersBySlot, restockedAt, nextRestock);
    }

    private ActiveOffer pickOfferForRarity(String rarityId, Map<String, ActiveOffer> offersByRewardId) {
        List<ActiveOffer> matches = new ArrayList<>();
        for (ActiveOffer offer : offersByRewardId.values()) {
            if (Objects.equals(offer.getRarity().getId(), rarityId)) {
                matches.add(offer);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }

        double totalWeight = 0.0D;
        for (ActiveOffer offer : matches) {
            totalWeight += Math.max(0.01D, offer.getReward().getChance());
        }

        double pick = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cursor = 0.0D;
        for (ActiveOffer offer : matches) {
            cursor += Math.max(0.01D, offer.getReward().getChance());
            if (pick <= cursor) {
                return offer;
            }
        }
        return matches.getFirst();
    }

    private boolean hasRequiredItems(Inventory inventory, ItemStack cost) {
        int remaining = cost.getAmount();
        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType().isAir()) {
                continue;
            }
            if (content.isSimilar(cost)) {
                remaining -= content.getAmount();
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeRequiredItems(Inventory inventory, ItemStack cost) {
        int remaining = cost.getAmount();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack content = contents[slot];
            if (content == null || content.getType().isAir() || !content.isSimilar(cost)) {
                continue;
            }
            int take = Math.min(remaining, content.getAmount());
            content.setAmount(content.getAmount() - take);
            if (content.getAmount() <= 0) {
                contents[slot] = new ItemStack(Material.AIR);
            }
            remaining -= take;
            if (remaining <= 0) {
                break;
            }
        }
        inventory.setStorageContents(contents);
    }

    private String prettify(Material material) {
        String[] split = material.name().toLowerCase().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : split) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private boolean requiresMoney(CostDefinition cost) {
        return cost.getType() == CostType.MONEY || cost.getType() == CostType.BOTH;
    }

    private boolean requiresItems(CostDefinition cost) {
        return cost.getType() == CostType.ITEM || cost.getType() == CostType.BOTH;
    }

    private String describeItemCost(ItemStack itemCost) {
        if (itemCost == null || itemCost.getType().isAir()) {
            return "Item cost not set";
        }
        return itemCost.getAmount() + "x " + getItemLabel(itemCost);
    }

    private String getItemLabel(ItemStack itemStack) {
        if (itemStack.hasItemMeta() && itemStack.getItemMeta() != null && itemStack.getItemMeta().hasDisplayName()) {
            return plugin.getMessages().renderTemplate(itemStack.getItemMeta().getDisplayName(), Map.of());
        }
        return prettify(itemStack.getType());
    }

    private void cacheOfferDisplay(ActiveOffer offer) {
        offer.setCostDisplay(describeCost(offer.getReward()));
        offer.setDisplayItem(buildOfferDisplayItem(offer, null));
    }

    private ItemStack buildOfferDisplayItem(ActiveOffer offer, Player viewer) {
        me.foesio.foBlackmarket.gui.GuiConfigManager.PlayerMarketGuiConfig guiConfig = plugin.getGuiConfigManager().playerMarket();
        ItemStack base = offer.getReward().getIcon();
        ItemStack icon = base == null || base.getType().isAir() ? new ItemStack(guiConfig.offerFallbackMaterial()) : base.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            int remainingStock = offer.getRemainingStock(viewer == null ? null : viewer.getUniqueId());
            String commandsLine = offer.getReward().getCommands().isEmpty()
                ? ""
                : plugin.getMessages().renderTemplate(guiConfig.commandsLine(), Map.of("commands", String.valueOf(offer.getReward().getCommands().size())));
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("reward", offer.getReward().getDisplayName());
            placeholders.put("reward_id", offer.getRewardId());
            placeholders.put("rarity", offer.getRarity().getDisplayName());
            placeholders.put("rarity_id", offer.getRarity().getId());
            placeholders.put("stock", String.valueOf(remainingStock));
            placeholders.put("stock_mode", offer.getReward().getStockMode().getDisplayName());
            placeholders.put("cost", offer.getCostDisplay());
            placeholders.put("chance", trimChance(offer.getReward().getChance()));
            placeholders.put("commands", String.valueOf(offer.getReward().getCommands().size()));
            placeholders.put("commands_line", commandsLine);
            placeholders.put("status_line", remainingStock > 0 ? guiConfig.buyLine() : guiConfig.soldOutLine());
            meta.setDisplayName(plugin.getMessages().renderTemplate(guiConfig.offerName(), placeholders));
            List<String> lore = new ArrayList<>();
            for (String line : guiConfig.offerLore()) {
                String rendered = plugin.getMessages().renderTemplate(line, placeholders);
                if (!rendered.isBlank()) {
                    lore.add(rendered);
                }
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String trimChance(double chance) {
        if (Math.floor(chance) == chance) {
            return String.valueOf((long) chance);
        }
        return String.format(java.util.Locale.US, "%.2f", chance)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }

    private record RewardRefreshResult(ActiveOffer offer, boolean refreshAllGuis) {

        private static RewardRefreshResult none() {
            return new RewardRefreshResult(null, false);
        }

        private static RewardRefreshResult fullRefresh() {
            return new RewardRefreshResult(null, true);
        }
    }
}
