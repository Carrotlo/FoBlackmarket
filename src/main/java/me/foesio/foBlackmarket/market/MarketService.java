package me.foesio.foBlackmarket.market;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.foesio.core.command.CommandPlaceholders;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.gui.publicgui.GuiDiagnostic;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.inventory.InventoryDepositResult;
import me.foesio.core.inventory.OverflowPolicy;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.api.MarketView;
import me.foesio.foBlackmarket.api.OfferView;
import me.foesio.foBlackmarket.api.PurchaseQuote;
import me.foesio.foBlackmarket.api.RestockCause;
import me.foesio.foBlackmarket.api.event.BlackmarketPurchaseCompletedEvent;
import me.foesio.foBlackmarket.api.event.BlackmarketPurchaseRequestEvent;
import me.foesio.foBlackmarket.api.event.BlackmarketRestockedEvent;
import me.foesio.foBlackmarket.config.ConfigManager;
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.gui.PublicMarketMenus;
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
    private final VaultEconomyBridge vaultEconomy;
    private final GuiManager guiManager;
    private final Object lock = new Object();
    private final Set<UUID> purchasesInProgress = new HashSet<>();

    private MarketData marketData;
    private MarketSnapshot snapshot;
    private long nextRestockAt;
    private int restockTaskId = -1;
    private boolean stagedStateNeedsSave;
    private boolean restockInProgress;

    public MarketService(
        FoBlackmarket plugin,
        ConfigManager configManager,
        MarketRepository marketRepository,
        VaultEconomyBridge vaultEconomy,
        GuiManager guiManager
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.marketRepository = marketRepository;
        this.vaultEconomy = vaultEconomy;
        this.guiManager = guiManager;
    }

    public void start() {
        activatePublicGuiReload();
    }

    public void shutdown() {
        try {
            synchronized (lock) {
                persistCurrentState();
            }
        } finally {
            if (restockTaskId != -1) {
                Bukkit.getScheduler().cancelTask(restockTaskId);
                restockTaskId = -1;
            }
        }
    }

    public PublicGuiService.PreparedDependency stagePublicGuiDependency(
        Map<String, PublicGuiDefinition> definitions
    ) {
        PublicGuiDefinition player = definitions.get(PublicMarketMenus.PLAYER);
        if (player == null) {
            return new PublicGuiService.PreparedDependency(
                List.of(new GuiDiagnostic("market.yml", "<gui>", "player market definition is missing")), () -> {});
        }
        try {
            List<Integer> slots = player.entries().get("offers").slots();
            MarketRepository.PreparedMarket prepared = marketRepository.loadStrict(slots);
            MarketData data = prepared.data();
            alignLayoutWithPublicGui(data, player);
            MarketSnapshot restored = restoreSnapshot(data, prepared.state());
            long now = System.currentTimeMillis();
            long next = restored == null ? now + restockIntervalMillis() : restored.getNextRestockAt();
            boolean needsSave = restored == null || next <= now;
            if (next <= now) next = now + restockIntervalMillis();
            MarketSnapshot staged = restored == null ? buildSnapshot(data, now, next)
                : next == restored.getNextRestockAt() ? restored : withNextRestock(restored, next);
            long finalNext = next;
            return PublicGuiService.PreparedDependency.ready(() -> {
                synchronized (lock) {
                    marketData = data;
                    snapshot = staged;
                    nextRestockAt = finalNext;
                    stagedStateNeedsSave = needsSave;
                }
            });
        } catch (Exception exception) {
            return new PublicGuiService.PreparedDependency(List.of(new GuiDiagnostic("market.yml", "<data>",
                exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())), () -> {});
        }
    }

    /** Called only after Core has published the compatible GUI and market pair. */
    public void activatePublicGuiReload() {
        synchronized (lock) {
            if (marketData == null || snapshot == null) {
                throw new IllegalStateException("No compatible market snapshot is active");
            }
            vaultEconomy.reload();
            if (stagedStateNeedsSave) {
                try {
                    persistCurrentState();
                    stagedStateNeedsSave = false;
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("Market is active, but its new stock state could not be saved: "
                        + exception.getMessage());
                }
            }
            scheduleRestocks();
        }
        guiManager.refreshPlayerMarketGuis();
    }

    public void persistMarketData(boolean broadcast) {
        restockNow(broadcast, RestockCause.EDITOR);
    }

    public void persistMarketDataWithoutRestock() {
        boolean refreshAllGuis;
        synchronized (lock) {
            requireActiveMarket();
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
            requireActiveMarket();
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
            requireActiveMarket();
            refreshAllGuis = removeActiveReward(rewardId);
            persistMarketDataWithCurrentState();
        }

        if (refreshAllGuis) {
            guiManager.refreshPlayerMarketGuis();
        }
    }

    public void rescheduleRestockFromNow() {
        synchronized (lock) {
            long proposedNext = System.currentTimeMillis() + restockIntervalMillis();
            if (snapshot != null) {
                MarketSnapshot proposed = withNextRestock(snapshot, proposedNext);
                marketRepository.saveState(proposed);
                this.snapshot = proposed;
                this.nextRestockAt = proposedNext;
                try {
                    scheduleRestocks();
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("Restock time changed, but its next timer could not be scheduled: "
                        + exception.getMessage());
                }
            } else {
                this.nextRestockAt = proposedNext;
            }
        }
        guiManager.refreshPlayerMarketGuis();
    }

    public void restockNow(boolean broadcast) {
        restockNow(broadcast, RestockCause.DIRECT);
    }

    public void restockNow(boolean broadcast, RestockCause cause) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Market restocks require the Bukkit server thread");
        }
        Objects.requireNonNull(cause, "cause");
        synchronized (lock) {
            if (restockInProgress) {
                throw new IllegalStateException("A market restock is already in progress");
            }
            restockInProgress = true;
        }
        try {
            completeRestock(broadcast, cause);
        } finally {
            synchronized (lock) {
                restockInProgress = false;
            }
        }
    }

    private void completeRestock(boolean broadcast, RestockCause cause) {
        long restockedAt;
        long nextRestock;
        synchronized (lock) {
            requireActiveMarket();
            long now = System.currentTimeMillis();
            long proposedNext = now + restockIntervalMillis();
            MarketSnapshot proposed = buildSnapshot(marketData, now, proposedNext);
            marketRepository.saveState(proposed);
            this.nextRestockAt = proposedNext;
            this.snapshot = proposed;
            try {
                scheduleRestocks();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Market restocked, but its next timer could not be scheduled: "
                    + exception.getMessage());
            }
            restockedAt = now;
            nextRestock = proposedNext;
        }

        Bukkit.getPluginManager().callEvent(new BlackmarketRestockedEvent(cause, restockedAt, nextRestock));
        try {
            guiManager.refreshPlayerMarketGuis();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Market restocked, but a GUI refresh failed: " + exception.getMessage());
        }
        if (broadcast) {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    plugin.getSounds().play(player, "market.restock");
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Market restock sound failed: " + exception.getMessage());
            }
        }
        if (broadcast && configManager.isAnnounceRestocks()) {
            try {
                Map<String, String> values = Map.of("time_remaining",
                    configManager.formatDuration(getTimeUntilRestock()));
                if (!plugin.getMessages().render("messages.market-restocked-broadcast", "", values).isBlank()) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        plugin.getMessages().send(player, "messages.market-restocked-broadcast", "", values);
                    }
                    plugin.getMessages().send(Bukkit.getConsoleSender(),
                        "messages.market-restocked-broadcast", "", values);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Market restock announcement failed: " + exception.getMessage());
            }
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

    /** Copies only offers currently displayed in player-market slots. */
    public Optional<MarketView> currentMarket(UUID playerId) {
        synchronized (lock) {
            if (snapshot == null || playerId == null) return Optional.empty();
            List<OfferView> offers = new ArrayList<>();
            for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
                ActiveOffer offer = entry.getValue();
                offers.add(new OfferView(entry.getKey(), offer.getRewardId(), offer.getReward().getDisplayName(),
                    offer.getRarity().getId(), offer.getRemainingStock(playerId), describeCost(offer.getReward())));
            }
            return Optional.of(new MarketView(snapshot.getRestockedAt(), snapshot.getNextRestockAt(), offers));
        }
    }

    public PurchaseQuote quotePurchase(Player player, String rewardId) {
        synchronized (lock) {
            return quotePurchaseLocked(player, rewardId);
        }
    }

    private PurchaseQuote quotePurchaseLocked(Player player, String rewardId) {
        if (snapshot == null || player == null || rewardId == null || rewardId.isBlank()) {
            return quote(PurchaseQuote.Status.UNAVAILABLE, rewardId, 0, "");
        }
        ActiveOffer offer = snapshot.getOffersByRewardId().get(rewardId);
        if (offer == null || !snapshot.getOffersBySlot().containsValue(offer)) {
            return quote(PurchaseQuote.Status.UNAVAILABLE, rewardId, 0, "");
        }
        int remainingStock = offer.getRemainingStock(player.getUniqueId());
        if (remainingStock <= 0) return quote(PurchaseQuote.Status.SOLD_OUT, rewardId, 0, "");
        RewardDefinition reward = offer.getReward();
        CostDefinition cost = reward.getCost();
        ItemStack rewardItem = reward.getRewardItem();
        boolean hasRewardItem = rewardItem != null && !rewardItem.getType().isAir();
        if (!hasRewardItem && reward.getCommands().isEmpty()) {
            return quote(PurchaseQuote.Status.FAILED, rewardId, remainingStock, "");
        }
        if (hasRewardItem && !plugin.getCore().inventoryDeposits().canFitAll(player, rewardItem)) {
            return quote(PurchaseQuote.Status.INVENTORY_FULL, rewardId, remainingStock, "");
        }
        if (requiresMoney(cost)) {
            double moneyCost = Math.max(0.0D, cost.getMoneyCost());
            if (!vaultEconomy.isAvailable() || !vaultEconomy.has(player, moneyCost)) {
                return quote(PurchaseQuote.Status.NOT_ENOUGH_MONEY, rewardId, remainingStock,
                    vaultEconomy.formatCompact(moneyCost));
            }
        }
        if (requiresItems(cost)) {
            ItemStack itemCost = cost.getItemCost();
            if (itemCost == null || itemCost.getType().isAir() || !hasRequiredItems(player.getInventory(), itemCost)) {
                return quote(PurchaseQuote.Status.NOT_ENOUGH_ITEMS, rewardId, remainingStock, "");
            }
        }
        return quote(PurchaseQuote.Status.AVAILABLE, rewardId, remainingStock, "");
    }

    private PurchaseQuote quote(PurchaseQuote.Status status, String rewardId, int remainingStock, String priceText) {
        return new PurchaseQuote(status, rewardId, remainingStock, priceText);
    }

    public PurchaseResult purchase(Player player, String rewardId) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Market purchases require the Bukkit server thread");
        }
        if (player == null) return PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
        UUID playerId = player.getUniqueId();
        synchronized (lock) {
            if (!purchasesInProgress.add(playerId)) {
                return PurchaseResult.of(PurchaseResult.Status.FAILED);
            }
        }
        try {
            return completePurchase(player, rewardId);
        } finally {
            synchronized (lock) {
                purchasesInProgress.remove(playerId);
            }
        }
    }

    private PurchaseResult completePurchase(Player player, String rewardId) {
        PurchaseQuote initial = quotePurchase(player, rewardId);
        if (!initial.canPurchase()) return failedPurchase(initial);
        BlackmarketPurchaseRequestEvent request = new BlackmarketPurchaseRequestEvent(player, initial);
        Bukkit.getPluginManager().callEvent(request);
        if (request.isCancelled()) return PurchaseResult.of(PurchaseResult.Status.CANCELLED);

        PurchaseResult result;
        ActiveOffer updatedOffer = null;
        List<String> rewardCommands;
        synchronized (lock) {
            PurchaseQuote current = quotePurchaseLocked(player, rewardId);
            if (!current.canPurchase()) return failedPurchase(current);
            ActiveOffer offer = snapshot.getOffersByRewardId().get(rewardId);
            int remainingStock;
            RewardDefinition reward = offer.getReward();
            CostDefinition cost = reward.getCost();
            ItemStack rewardItem = reward.getRewardItem();
            boolean hasRewardItem = rewardItem != null && !rewardItem.getType().isAir();
            double moneyCost = requiresMoney(cost) ? Math.max(0.0D, cost.getMoneyCost()) : 0.0D;
            boolean moneyWithdrawn = false;

            if (requiresMoney(cost)) {
                if (!vaultEconomy.withdraw(player, moneyCost)) {
                    return PurchaseResult.of(PurchaseResult.Status.FAILED);
                }
                moneyWithdrawn = true;
            }
            if (snapshot.getOffersByRewardId().get(rewardId) != offer) {
                refundMoney(player, moneyCost, moneyWithdrawn);
                return PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
            }

            ItemStack[] originalItems = copyStorage(player.getInventory().getStorageContents());
            int oldServerStock = offer.getRemainingStock();
            Map<UUID, Integer> oldPlayerStock = offer.getPerPlayerRemainingStock();
            try {
                if (requiresItems(cost)) {
                    removeRequiredItems(player.getInventory(), cost.getItemCost());
                }
                if (hasRewardItem) {
                    InventoryDepositResult deposit = plugin.getCore().inventoryDeposits()
                        .deposit(player, rewardItem.clone(), OverflowPolicy.BLOCK_COLLECTION);
                    if (!deposit.fullyAccepted()) {
                        rollbackPlayerPayment(player, originalItems, moneyCost, moneyWithdrawn);
                        return PurchaseResult.of(PurchaseResult.Status.INVENTORY_FULL);
                    }
                }
                remainingStock = offer.decrementRemainingStock(player.getUniqueId());
                persistCurrentState();
            } catch (RuntimeException exception) {
                offer.setRemainingStock(oldServerStock);
                offer.setPerPlayerRemainingStock(oldPlayerStock);
                rollbackPlayerPayment(player, originalItems, moneyCost, moneyWithdrawn);
                plugin.getLogger().severe("Purchase could not be saved or delivered for " + rewardId
                    + ": " + exception.getMessage());
                return PurchaseResult.of(PurchaseResult.Status.FAILED);
            }
            updatedOffer = offer;
            result = PurchaseResult.success(reward.getDisplayName(), remainingStock);
            rewardCommands = reward.getCommands();
        }
        Bukkit.getPluginManager().callEvent(new BlackmarketPurchaseCompletedEvent(player, rewardId,
            result.getRemainingStock()));
        for (String command : rewardCommands) {
            try {
                String resolved = CommandPlaceholders.apply(command, Map.of(
                    "player", player.getName(),
                    "player_uuid", player.getUniqueId().toString(),
                    "uuid", player.getUniqueId().toString()
                ));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Reward command failed after purchase of " + rewardId
                    + ": " + exception.getMessage());
            }
        }
        guiManager.refreshPlayerMarketOffer(updatedOffer);
        return result;
    }

    private ItemStack[] copyStorage(ItemStack[] contents) {
        return Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }

    private void refundMoney(Player player, double amount, boolean withdrawn) {
        if (withdrawn) {
            try {
                if (!vaultEconomy.deposit(player, amount)) {
                    plugin.getLogger().severe("Could not refund " + amount + " to " + player.getUniqueId()
                        + " after a failed purchase");
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Could not refund " + amount + " to " + player.getUniqueId()
                    + " after a failed purchase: " + exception.getMessage());
            }
        }
    }

    private void rollbackPlayerPayment(Player player, ItemStack[] originalItems, double amount, boolean withdrawn) {
        try {
            player.getInventory().setStorageContents(originalItems);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not restore inventory for " + player.getUniqueId()
                + " after a failed purchase: " + exception.getMessage());
        }
        refundMoney(player, amount, withdrawn);
    }

    private PurchaseResult failedPurchase(PurchaseQuote quote) {
        return switch (quote.status()) {
            case NOT_ENOUGH_MONEY -> PurchaseResult.moneyFailure(quote.priceText());
            case SOLD_OUT -> PurchaseResult.of(PurchaseResult.Status.SOLD_OUT);
            case NOT_ENOUGH_ITEMS -> PurchaseResult.of(PurchaseResult.Status.NOT_ENOUGH_ITEMS);
            case INVENTORY_FULL -> PurchaseResult.of(PurchaseResult.Status.INVENTORY_FULL);
            case FAILED -> PurchaseResult.of(PurchaseResult.Status.FAILED);
            default -> PurchaseResult.of(PurchaseResult.Status.UNAVAILABLE);
        };
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
            return vaultEconomy.formatCompact(cost.getMoneyCost());
        }
        if (cost.getType() == CostType.ITEM) {
            return describeItemCost(cost.getItemCost());
        }
        return vaultEconomy.formatCompact(cost.getMoneyCost()) + " + " + describeItemCost(cost.getItemCost());
    }

    public String describePublicCost(RewardDefinition reward) {
        CostDefinition cost = reward.getCost();
        String money = plugin.getMessages().render("gui.cost-money", "",
            Map.of("money", vaultEconomy.formatCompact(cost.getMoneyCost())));
        if (cost.getType() == CostType.MONEY) {
            return money;
        }
        String item = describePublicItemCost(cost.getItemCost());
        if (cost.getType() == CostType.ITEM) {
            return item;
        }
        return plugin.getMessages().render("gui.cost-both", "",
            Map.of("money", money, "item", item));
    }

    private void scheduleRestocks() {
        if (restockTaskId != -1) {
            Bukkit.getScheduler().cancelTask(restockTaskId);
        }
        restockTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(
            plugin,
            () -> restockNow(true, RestockCause.SCHEDULED),
            delayTicksUntilNextRestock()
        );
    }

    private void persistCurrentState() {
        if (marketData != null && snapshot != null) {
            marketRepository.saveState(snapshot);
        }
    }

    private void requireActiveMarket() {
        if (marketData == null || snapshot == null) {
            throw new IllegalStateException("Public market is unavailable while its GUI migration is pending");
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

    private void alignLayoutWithPublicGui(MarketData data, PublicGuiDefinition player) {
        List<Integer> configuredSlots = player.entries().get("offers").slots();
        Map<Integer, String> oldAssignments = data.getLayout().getSlotRarities();
        Map<Integer, String> assignments = new LinkedHashMap<>();
        if (configuredSlots.containsAll(oldAssignments.keySet())) {
            for (int slot : configuredSlots) {
                String rarity = oldAssignments.get(slot);
                if (rarity != null) {
                    assignments.put(slot, rarity);
                }
            }
        } else {
            List<Map.Entry<Integer, String>> ordered = new ArrayList<>(oldAssignments.entrySet());
            ordered.sort(Comparator.comparingInt(Map.Entry::getKey));
            for (int index = 0; index < Math.min(configuredSlots.size(), ordered.size()); index++) {
                assignments.put(configuredSlots.get(index), ordered.get(index).getValue());
            }
        }
        data.getLayout().setSlotRarities(assignments);
        data.getLayout().setSize(player.size());
        data.getLayout().setCloseSlot(-1);
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
            offersByRewardId.put(reward.getId(), offer);
        }

        if (offersByRewardId.isEmpty()) {
            return null;
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        List<String> savedIds = new ArrayList<>(state.slotRewardIds().values());
        for (Map.Entry<Integer, String> assignment : data.getLayout().getSlotRarities().entrySet()) {
            int slot = assignment.getKey();
            ActiveOffer offer = offersByRewardId.get(state.slotRewardIds().get(slot));
            if (offer == null || !offer.getRarity().getId().equals(assignment.getValue())) {
                offer = null;
                for (String rewardId : savedIds) {
                    ActiveOffer candidate = offersByRewardId.get(rewardId);
                    if (candidate != null && candidate.getRarity().getId().equals(assignment.getValue())) {
                        offer = candidate;
                        savedIds.remove(rewardId);
                        break;
                    }
                }
            } else {
                savedIds.remove(offer.getRewardId());
            }
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
            offersByRewardId.put(reward.getId(), offer);
        }

        Map<Integer, ActiveOffer> offersBySlot = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : data.getLayout().getSlotRarities().entrySet()) {
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

    private String describePublicItemCost(ItemStack itemCost) {
        if (itemCost == null || itemCost.getType().isAir()) {
            return plugin.getMessages().render("gui.cost-missing-item", "");
        }
        String label = itemCost.hasItemMeta() && itemCost.getItemMeta() != null
            && itemCost.getItemMeta().hasDisplayName()
            ? plugin.getMessages().renderTemplate(itemCost.getItemMeta().getDisplayName(), Map.of())
            : plugin.getMessages().render("gui.cost-unnamed-item", "");
        return plugin.getMessages().render("gui.cost-item", "",
            Map.of("amount", configManager.formatCount(itemCost.getAmount()), "item", label));
    }

    private String getItemLabel(ItemStack itemStack) {
        if (itemStack.hasItemMeta() && itemStack.getItemMeta() != null && itemStack.getItemMeta().hasDisplayName()) {
            return plugin.getMessages().renderTemplate(itemStack.getItemMeta().getDisplayName(), Map.of());
        }
        return prettify(itemStack.getType());
    }

    public String formatChance(double chance) {
        String tag = plugin.getMessages().render("gui.number-locale", "");
        java.util.Locale locale = tag.isBlank() ? java.util.Locale.ROOT : java.util.Locale.forLanguageTag(tag);
        java.text.NumberFormat number = java.text.NumberFormat.getNumberInstance(locale);
        number.setMaximumFractionDigits(2);
        number.setGroupingUsed(false);
        return number.format(chance);
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
