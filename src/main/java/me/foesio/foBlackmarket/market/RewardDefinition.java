package me.foesio.foBlackmarket.market;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public final class RewardDefinition {

    private String id;
    private String displayName;
    private String rarityId;
    private double chance;
    private int stock;
    private StockMode stockMode;
    private ItemStack icon;
    private ItemStack rewardItem;
    private List<String> commands;
    private CostDefinition cost;

    public RewardDefinition(
        String id,
        String displayName,
        String rarityId,
        double chance,
        int stock,
        StockMode stockMode,
        ItemStack icon,
        ItemStack rewardItem,
        List<String> commands,
        CostDefinition cost
    ) {
        this.id = id;
        this.displayName = displayName;
        this.rarityId = rarityId;
        this.chance = chance;
        this.stock = stock;
        this.stockMode = stockMode == null ? StockMode.SERVER_WIDE : stockMode;
        this.icon = icon == null ? null : icon.clone();
        this.rewardItem = rewardItem == null ? null : rewardItem.clone();
        this.commands = new ArrayList<>(commands);
        this.cost = cost;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRarityId() {
        return rarityId;
    }

    public void setRarityId(String rarityId) {
        this.rarityId = rarityId;
    }

    public double getChance() {
        return chance;
    }

    public void setChance(double chance) {
        this.chance = chance;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public StockMode getStockMode() {
        return stockMode;
    }

    public void setStockMode(StockMode stockMode) {
        this.stockMode = stockMode == null ? StockMode.SERVER_WIDE : stockMode;
    }

    public ItemStack getIcon() {
        return icon == null ? null : icon.clone();
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon == null ? null : icon.clone();
    }

    public ItemStack getRewardItem() {
        return rewardItem == null ? null : rewardItem.clone();
    }

    public void setRewardItem(ItemStack rewardItem) {
        this.rewardItem = rewardItem == null ? null : rewardItem.clone();
    }

    public List<String> getCommands() {
        return new ArrayList<>(commands);
    }

    public void setCommands(List<String> commands) {
        this.commands = new ArrayList<>(commands);
    }

    public CostDefinition getCost() {
        return cost;
    }

    public void setCost(CostDefinition cost) {
        this.cost = cost;
    }
}
