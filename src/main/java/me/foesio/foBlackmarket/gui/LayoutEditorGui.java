package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.MarketLayout;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

public final class LayoutEditorGui extends BaseGui {

    private static final int ROWS = 6;
    private static final int HELP_SLOT = 52;

    public LayoutEditorGui(FoBlackmarket plugin, Player viewer) {
        super(plugin, viewer, 54, plugin.title("layout"));
    }

    @Override
    protected void render() {
        MarketLayout layout = plugin.getMarketService().getMarketData().getLayout();
        List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
        for (int slot = 0; slot < 45; slot++) {
            if (slot >= layout.getSize()) {
                inventory.setItem(slot, item(Material.BLACK_STAINED_GLASS_PANE, "#a7b8b0Outside Layout", "#ffffffIncrease GUI size to use this slot."));
                continue;
            }
            if (slot == layout.getInfoSlot()) {
                inventory.setItem(slot, item(Material.CLOCK, "#03fc88Info Slot", "#ffffffReserved for market info."));
                continue;
            }
            if (slot == layout.getCloseSlot()) {
                inventory.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE, "#a7b8b0Reserved Slot", "#ffffffReserved as filler in the player market."));
                continue;
            }

            String rarityId = layout.getSlotRarities().get(slot);
            RarityDefinition rarity = rarityId == null ? null : plugin.getMarketService().getMarketData().getRarities().get(rarityId);
            inventory.setItem(slot, createLayoutSlotButton(rarityIds, rarityId, rarity));
        }

        inventory.setItem(45, item(Material.CHEST, "#03fc88GUI Size", "#ffffffCurrent: #03fc88" + layout.getSize(), "#ffffffClick to edit. Use multiples of 9."));
        inventory.setItem(46, item(Material.CLOCK, "#03fc88Info Slot", "#ffffffCurrent: #03fc88" + layout.getInfoSlot(), "#ffffffClick to edit."));
        inventory.setItem(47, item(Material.GRAY_STAINED_GLASS_PANE, "#03fc88Reserved Slot", "#ffffffCurrent: #03fc88" + layout.getCloseSlot(), "#ffffffClick to edit."));
        inventory.setItem(48, createFillerPreview(layout.getFillerItem()));
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back());
        inventory.setItem(HELP_SLOT, item(Material.BOOK, "#03fc88Editor Help", "#ffffffClick top slots to cycle rarities.", "#ffffffSlots never become air while a rarity pool exists."));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        MarketLayout layout = plugin.getMarketService().getMarketData().getLayout();
        int slot = event.getRawSlot();
        if (slot >= 0 && slot < 45) {
            if (slot >= layout.getSize() || slot == layout.getInfoSlot() || slot == layout.getCloseSlot()) {
                return;
            }
            cycleSlotRarity(slot, event.isRightClick());
            return;
        }

        if (slot == bottomMiddleSlot(ROWS)) {
            editorSounds.back(viewer);
            plugin.getGuiManager().openMainEditorSilently(viewer);
            return;
        }

        switch (slot) {
            case 45 -> promptSlotValue("Enter the GUI size (9-54, multiples of 9).", String.valueOf(layout.getSize()), value -> {
                int size = Math.max(9, Math.min(54, value));
                size -= size % 9;
                final int finalSize = size;
                layout.setSize(size);
                layout.getSlotRarities().entrySet().removeIf(entry -> entry.getKey() >= finalSize);
                if (layout.getInfoSlot() >= finalSize) {
                    layout.setInfoSlot(Math.max(0, finalSize - 5));
                }
                if (layout.getCloseSlot() >= finalSize) {
                    layout.setCloseSlot(Math.max(0, finalSize - 1));
                }
            }, "Use a whole number between 9 and 54.");
            case 46 -> promptSlotValue("Enter the info slot.", String.valueOf(layout.getInfoSlot()), value -> {
                if (value >= 0 && value < layout.getSize()) {
                    layout.setInfoSlot(value);
                    layout.getSlotRarities().remove(value);
                }
            }, "Use a slot inside the current GUI size.");
            case 47 -> promptSlotValue("Enter the reserved slot.", String.valueOf(layout.getCloseSlot()), value -> {
                if (value >= 0 && value < layout.getSize()) {
                    layout.setCloseSlot(value);
                    layout.getSlotRarities().remove(value);
                }
            }, "Use a slot inside the current GUI size.");
            case 48 -> {
                if (event.isRightClick()) {
                    layout.setFillerItem(null);
                    editorSounds.delete(viewer);
                    saveAndReopen("layout filler");
                    return;
                }
                ItemStack item = inputItem(event.getCursor());
                if (item == null) {
                    editorSounds.addItemError(viewer);
                    plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold an item in your main hand first."));
                    return;
                }
                layout.setFillerItem(item);
                editorSounds.addItem(viewer);
                saveAndReopen("layout filler");
            }
            default -> {
            }
        }
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        if (event.getRawSlots().contains(48)) {
            ItemStack item = inputItem(event.getOldCursor());
            if (item != null) {
                plugin.getMarketService().getMarketData().getLayout().setFillerItem(item);
                editorSounds.addItem(viewer);
                saveAndReopen("layout filler");
            }
        }
    }

    @Override
    public boolean allowsPlayerInventoryInteraction() {
        return true;
    }

    private void cycleSlotRarity(int slot, boolean clear) {
        MarketLayout layout = plugin.getMarketService().getMarketData().getLayout();
        Map<Integer, String> slotRarities = layout.getSlotRarities();
        if (clear) {
            slotRarities.remove(slot);
            editorSounds.delete(viewer);
            saveAndReopen("layout slot");
            return;
        }

        List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
        if (rarityIds.isEmpty()) {
            editorSounds.error(viewer);
            plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Create a rarity first."));
            return;
        }
        String current = slotRarities.get(slot);
        int index = current == null ? -1 : rarityIds.indexOf(current);
        String next = rarityIds.get((index + 1 + rarityIds.size()) % rarityIds.size());
        slotRarities.put(slot, next);
        editorSounds.cycle(viewer);
        saveAndReopen("layout slot");
    }

    private void promptSlotValue(String prompt, String currentValue, java.util.function.IntConsumer consumer, String invalidReason) {
        openEditorInput(editorNumberRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Integer.parseInt(input.trim()));
                plugin.getMarketService().persistMarketData(false);
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "layout"));
            } catch (NumberFormatException exception) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openLayoutEditorSilently(viewer));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openLayoutEditorSilently(viewer);
        });
    }

    private void saveAndReopen(String target) {
        plugin.getMarketService().persistMarketData(false);
        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", target));
        plugin.getGuiManager().openLayoutEditorSilently(viewer);
    }

    private ItemStack createLayoutSlotButton(List<String> rarityIds, String rarityId, RarityDefinition rarity) {
        List<String> lore = new ArrayList<>();
        if (rarity == null) {
            lore.add("#ffffffCurrent: #a7b8b0None");
            lore.add("#ffffffClick to assign a rarity.");
        } else {
            lore.add("#ffffffRarity ID: #03fc88" + rarity.getId());
            lore.add("#ffffffClick to cycle. Right click to clear.");
        }

        if (rarityIds.isEmpty()) {
            lore.add("#a7b8b0Create a rarity first.");
        } else {
            lore.addAll(CycleOptions.lore(
                plugin.getMessages(),
                rarity == null ? null : rarityId,
                rarityIds.stream()
                    .map(id -> new CycleOption(id, id))
                    .toList()
            ));
        }

        if (rarity == null) {
            return item(Material.GRAY_STAINED_GLASS_PANE, "#a7b8b0Empty Slot", lore);
        }
        return item(rarity.getIcon() == null ? Material.NETHER_STAR : rarity.getIcon().getType(), rarity.getDisplayName(), lore);
    }

    private ItemStack createFillerPreview(ItemStack current) {
        ItemStack preview = current == null || current.getType().isAir() ? new ItemStack(Material.ITEM_FRAME) : current.clone();
        org.bukkit.inventory.meta.ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FoText.color("#03fc88Filler Item"));
            java.util.List<String> lore = new java.util.ArrayList<>();
            if (current == null || current.getType().isAir()) {
                lore.add(FoText.color("#ffffffCurrent: #a7b8b0Not set"));
            } else {
                lore.add(FoText.color("#ffffffMaterial: #03fc88" + current.getType().name()));
                lore.add(FoText.color("#ffffffAmount: #03fc88" + current.getAmount()));
                if (meta.hasDisplayName()) {
                    lore.add(FoText.color("#ffffffCustom Name: ") + meta.getDisplayName());
                } else {
                    lore.add(FoText.color("#ffffffCustom Name: #a7b8b0None"));
                }
                if (meta.hasLore()) {
                    lore.add(FoText.color("#ffffffLore Lines: #03fc88" + meta.getLore().size()));
                } else {
                    lore.add(FoText.color("#ffffffLore Lines: #a7b8b0None"));
                }
            }
            lore.add(FoText.color("#ffffffChanges only the live player shop filler."));
            lore.add(FoText.color("#ffffffClick or drag an item here to set it."));
            lore.add(FoText.color("#ffffffRight click to clear."));
            meta.setLore(lore);
            preview.setItemMeta(meta);
        }
        return preview;
    }
}
