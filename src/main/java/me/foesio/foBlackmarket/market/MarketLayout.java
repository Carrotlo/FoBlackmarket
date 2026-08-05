package me.foesio.foBlackmarket.market;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

public final class MarketLayout {

    private int size;
    private int infoSlot;
    private int closeSlot;
    private ItemStack fillerItem;
    private Map<Integer, String> slotRarities;

    public MarketLayout(int size, int infoSlot, int closeSlot, ItemStack fillerItem, Map<Integer, String> slotRarities) {
        this.size = size;
        this.infoSlot = infoSlot;
        this.closeSlot = closeSlot;
        this.fillerItem = fillerItem == null ? null : fillerItem.clone();
        this.slotRarities = new HashMap<>(slotRarities);
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int getInfoSlot() {
        return infoSlot;
    }

    public void setInfoSlot(int infoSlot) {
        this.infoSlot = infoSlot;
    }

    public int getCloseSlot() {
        return closeSlot;
    }

    public void setCloseSlot(int closeSlot) {
        this.closeSlot = closeSlot;
    }

    public ItemStack getFillerItem() {
        return fillerItem == null ? null : fillerItem.clone();
    }

    public void setFillerItem(ItemStack fillerItem) {
        this.fillerItem = fillerItem == null ? null : fillerItem.clone();
    }

    public Map<Integer, String> getSlotRarities() {
        return slotRarities;
    }

    public void setSlotRarities(Map<Integer, String> slotRarities) {
        this.slotRarities = new HashMap<>(slotRarities);
    }
}
