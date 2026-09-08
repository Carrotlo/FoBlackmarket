package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.RarityDefinition;
import me.foesio.foBlackmarket.market.RewardDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RarityEditGui extends BaseGui {

    private static final int ROWS = 3;
    private final String rarityId;

    public RarityEditGui(FoBlackmarket plugin, Player viewer, String rarityId) {
        super(plugin, viewer, 27, plugin.title("rarity-edit"));
        this.rarityId = rarityId;
    }

    @Override
    protected void render() {
        RarityDefinition rarity = getRarity();
        if (rarity == null) {
            plugin.getGuiManager().openRarityListSilently(viewer, 0);
            return;
        }

        inventory.setItem(10, button(Material.NAME_TAG, "#03fc88", "Rarity ID",
            List.of("Current: " + rarity.getId(), "Click to rename."), "rename the rarity"));
        inventory.setItem(12, button(Material.WRITABLE_BOOK, "#03fc88", "Display Name",
            List.of("Current: " + rarity.getDisplayName(), "Click to edit."), "edit the display name"));
        inventory.setItem(14, createIconButton(rarity));
        inventory.setItem(16, button(Material.LAVA_BUCKET, "#ff5d73", "Delete Rarity",
            List.of("Shift-click to delete this rarity.", "Deletion is blocked if it is still in use."), "open confirmation"));
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back(viewer));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        RarityDefinition rarity = getRarity();
        if (rarity == null) {
            plugin.getGuiManager().openRarityListSilently(viewer, 0);
            return;
        }

        switch (event.getRawSlot()) {
            case 10 -> promptRenameId(rarity);
            case 12 -> {
                openEditorInput(editorTextRequest("Enter the rarity display name.", rarity.getDisplayName()), input -> {
                    rarity.setDisplayName(input);
                    plugin.getMarketService().persistMarketDataWithoutRestock();
                    editorSounds.save(viewer);
                    plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "rarity display name"));
                    runForViewer(() -> plugin.getGuiManager().openRarityEditorSilently(viewer, rarity.getId()));
                }, () -> {
                    plugin.getMessages().send(viewer, "messages.editor-cancelled");
                    plugin.getGuiManager().openRarityEditorSilently(viewer, rarity.getId());
                });
            }
            case 14 -> {
                if (event.getClick() == ClickType.RIGHT) {
                    rarity.setIcon(null);
                    editorSounds.delete(viewer);
                    saveAndRefresh(rarity, "rarity icon");
                    return;
                }
                ItemStack item = inputItem(event.getCursor());
                if (item == null) {
                    editorSounds.addItemError(viewer);
                    plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold an item in your main hand first."));
                    return;
                }
                rarity.setIcon(item);
                editorSounds.addItem(viewer);
                saveAndRefresh(rarity, "rarity icon");
            }
            case 16 -> {
                if (!event.getClick().isShiftClick()) {
                    editorSounds.error(viewer);
                    return;
                }
                if (isRarityInUse(rarity.getId())) {
                    editorSounds.error(viewer);
                    plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That rarity is still used by rewards or layout slots."));
                    return;
                }
                plugin.getMarketService().getMarketData().getRarities().remove(rarity.getId());
                plugin.getMarketService().persistMarketDataWithoutRestock();
                editorSounds.delete(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "rarity deletion"));
                plugin.getGuiManager().openRarityListSilently(viewer, 0);
            }
            case 22 -> {
                editorSounds.back(viewer);
                plugin.getGuiManager().openRarityListSilently(viewer, 0);
            }
            default -> {
            }
        }
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        if (!event.getRawSlots().contains(14)) {
            return;
        }
        RarityDefinition rarity = getRarity();
        ItemStack item = inputItem(event.getOldCursor());
        if (rarity != null && item != null) {
            rarity.setIcon(item);
            editorSounds.addItem(viewer);
            saveAndRefresh(rarity, "rarity icon");
        }
    }

    @Override
    public boolean allowsPlayerInventoryInteraction() {
        return true;
    }

    private RarityDefinition getRarity() {
        return plugin.getMarketService().getMarketData().getRarities().get(rarityId);
    }

    private void promptRenameId(RarityDefinition rarity) {
        openEditorInput(editorTextRequest("Enter the new rarity id.", rarity.getId()), input -> {
            String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_").trim();
            if (id.isBlank()) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Rarity ids must contain letters or numbers."));
            } else if (!id.equalsIgnoreCase(rarity.getId()) && plugin.getMarketService().getMarketData().getRarities().containsKey(id)) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That rarity id already exists."));
            } else {
                plugin.getMarketService().getMarketData().getRarities().remove(rarity.getId());
                String oldId = rarity.getId();
                rarity.setId(id);
                plugin.getMarketService().getMarketData().getRarities().put(id, rarity);
                for (RewardDefinition reward : plugin.getMarketService().getMarketData().getRewards().values()) {
                    if (oldId.equalsIgnoreCase(reward.getRarityId())) {
                        reward.setRarityId(id);
                    }
                }
                Map<Integer, String> slotRarities = plugin.getMarketService().getMarketData().getLayout().getSlotRarities();
                for (Map.Entry<Integer, String> entry : slotRarities.entrySet()) {
                    if (oldId.equalsIgnoreCase(entry.getValue())) {
                        entry.setValue(id);
                    }
                }
                plugin.getMarketService().persistMarketDataWithoutRestock();
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "rarity id"));
                runForViewer(() -> plugin.getGuiManager().openRarityEditorSilently(viewer, id));
                return;
            }
            runForViewer(() -> plugin.getGuiManager().openRarityEditorSilently(viewer, rarity.getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRarityEditorSilently(viewer, rarity.getId());
        });
    }

    private boolean isRarityInUse(String id) {
        if (plugin.getMarketService().getMarketData().getLayout().getSlotRarities().containsValue(id)) {
            return true;
        }
        for (RewardDefinition reward : plugin.getMarketService().getMarketData().getRewards().values()) {
            if (id.equalsIgnoreCase(reward.getRarityId())) {
                return true;
            }
        }
        return false;
    }

    private void saveAndRefresh(RarityDefinition rarity, String target) {
        plugin.getMarketService().persistMarketDataWithoutRestock();
        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", target));
        plugin.getGuiManager().openRarityEditorSilently(viewer, rarity.getId());
    }

    private ItemStack createIconButton(RarityDefinition rarity) {
        ItemStack current = rarity.getIcon();
        ItemStack button = current == null || current.getType().isAir() ? new ItemStack(Material.NETHER_STAR) : current.clone();
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FoText.color("#03fc88Display Icon"));
            List<String> lore = new ArrayList<>();
            if (current == null || current.getType().isAir()) {
                lore.add("§fCurrent: §7Not set");
            } else {
                lore.add("§fCurrent Material: §a" + current.getType().name());
                lore.add("§fAmount: §a" + current.getAmount());
                ItemMeta currentMeta = current.getItemMeta();
                if (currentMeta != null && currentMeta.hasDisplayName()) {
                    lore.add("§fCustom Name: " + currentMeta.getDisplayName());
                } else {
                    lore.add("§fCustom Name: §7None");
                }
                if (currentMeta != null && currentMeta.hasLore()) {
                    lore.add("§fLore Lines: §a" + currentMeta.getLore().size());
                } else {
                    lore.add("§fLore Lines: §7None");
                }
            }
            lore.add("§fClick or drag an item here to set it.");
            lore.add("§fRight click to clear.");
            meta.setLore(lore);
            button.setItemMeta(meta);
        }
        return button;
    }

}
