package me.foesio.foBlackmarket.market;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public final class ActiveOffer {

    private final String rewardId;
    private final RewardDefinition reward;
    private final RarityDefinition rarity;
    private String costDisplay;
    private int remainingStock;
    private final Map<UUID, Integer> perPlayerRemainingStock = new HashMap<>();
    private ItemStack displayItem;

    public ActiveOffer(String rewardId, RewardDefinition reward, RarityDefinition rarity, int remainingStock) {
        this.rewardId = rewardId;
        this.reward = reward;
        this.rarity = rarity;
        this.remainingStock = remainingStock;
    }

    public String getRewardId() {
        return rewardId;
    }

    public RewardDefinition getReward() {
        return reward;
    }

    public RarityDefinition getRarity() {
        return rarity;
    }

    public String getCostDisplay() {
        return costDisplay;
    }

    public void setCostDisplay(String costDisplay) {
        this.costDisplay = costDisplay;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public int getRemainingStock(UUID playerId) {
        if (reward.getStockMode() != StockMode.PER_PLAYER || playerId == null) {
            return remainingStock;
        }
        return perPlayerRemainingStock.getOrDefault(playerId, Math.max(0, reward.getStock()));
    }

    public void setRemainingStock(int remainingStock) {
        this.remainingStock = Math.max(0, remainingStock);
    }

    public Map<UUID, Integer> getPerPlayerRemainingStock() {
        return new HashMap<>(perPlayerRemainingStock);
    }

    public void setPerPlayerRemainingStock(Map<UUID, Integer> remainingStock) {
        perPlayerRemainingStock.clear();
        if (remainingStock == null || remainingStock.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Integer> entry : remainingStock.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                perPlayerRemainingStock.put(entry.getKey(), Math.max(0, entry.getValue()));
            }
        }
    }

    public int decrementRemainingStock(UUID playerId) {
        if (reward.getStockMode() != StockMode.PER_PLAYER || playerId == null) {
            remainingStock = Math.max(0, remainingStock - 1);
            return remainingStock;
        }
        int updatedStock = Math.max(0, getRemainingStock(playerId) - 1);
        perPlayerRemainingStock.put(playerId, updatedStock);
        return updatedStock;
    }

    public ItemStack getDisplayItem() {
        return displayItem == null ? null : displayItem.clone();
    }

    public void setDisplayItem(ItemStack displayItem) {
        this.displayItem = displayItem == null ? null : displayItem.clone();
    }
}
