package me.foesio.foBlackmarket.market;

import org.bukkit.inventory.ItemStack;

public final class RarityDefinition {

    private String id;
    private String displayName;
    private ItemStack icon;

    public RarityDefinition(String id, String displayName, ItemStack icon) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon == null ? null : icon.clone();
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

    public ItemStack getIcon() {
        return icon == null ? null : icon.clone();
    }

    public void setIcon(ItemStack icon) {
        this.icon = icon == null ? null : icon.clone();
    }
}
