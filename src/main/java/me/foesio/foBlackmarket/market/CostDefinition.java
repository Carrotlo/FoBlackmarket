package me.foesio.foBlackmarket.market;

import org.bukkit.inventory.ItemStack;

public final class CostDefinition {

    private CostType type;
    private double moneyCost;
    private ItemStack itemCost;

    public CostDefinition(CostType type, double moneyCost, ItemStack itemCost) {
        this.type = type;
        this.moneyCost = moneyCost;
        this.itemCost = itemCost == null ? null : itemCost.clone();
    }

    public CostType getType() {
        return type;
    }

    public void setType(CostType type) {
        this.type = type;
    }

    public double getMoneyCost() {
        return moneyCost;
    }

    public void setMoneyCost(double moneyCost) {
        this.moneyCost = moneyCost;
    }

    public ItemStack getItemCost() {
        return itemCost == null ? null : itemCost.clone();
    }

    public void setItemCost(ItemStack itemCost) {
        this.itemCost = itemCost == null ? null : itemCost.clone();
    }
}
