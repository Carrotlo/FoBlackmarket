package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RarityListGui extends BaseGui {

    private static final int ROWS = 6;
    private static final int[] ENTRY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };
    private static final int PAGE_SIZE = ENTRY_SLOTS.length;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CREATE_SLOT = 47;
    private static final int NEXT_SLOT = 53;

    private final int page;
    private List<RarityDefinition> rarities;

    public RarityListGui(FoBlackmarket plugin, Player viewer, int page) {
        super(plugin, viewer, 54, plugin.title("rarities"));
        this.page = Math.max(0, page);
    }

    @Override
    protected void render() {
        rarities = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().values());
        int start = page * PAGE_SIZE;
        for (int index = 0; index < ENTRY_SLOTS.length; index++) {
            ItemStack item;
            int rarityIndex = start + index;
            if (rarityIndex < rarities.size()) {
                item = createRarityItem(rarities.get(rarityIndex));
            } else {
                item = emptyRaritySlot();
            }
            inventory.setItem(ENTRY_SLOTS[index], item);
        }

        int maxPage = maxPage(rarities.size());
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, buttons.previousPage(page, maxPage));
        }
        inventory.setItem(CREATE_SLOT, item(Material.ANVIL, "#03fc88Create Rarity", "#ffffffClick to create a new rarity."));
        if (page < maxPage) {
            inventory.setItem(NEXT_SLOT, buttons.nextPage(page, maxPage));
        }
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back());
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        int entryIndex = entryIndex(slot);
        if (entryIndex >= 0) {
            int index = page * PAGE_SIZE + entryIndex;
            if (index < rarities.size()) {
                plugin.getGuiManager().openRarityEditor(viewer, rarities.get(index).getId());
            }
            return;
        }

        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                plugin.getGuiManager().openRarityList(viewer, page - 1);
            }
            return;
        }
        if (slot == bottomMiddleSlot(ROWS)) {
            plugin.getGuiManager().openMainEditor(viewer);
            return;
        }
        if (slot == CREATE_SLOT) {
            EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorTextRequest("Enter a new rarity id.", ""), input -> {
                String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_").trim();
                if (id.isBlank()) {
                    plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Rarity ids must contain letters or numbers."));
                } else if (plugin.getMarketService().getMarketData().getRarities().containsKey(id)) {
                    plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That rarity id already exists."));
                } else {
                    plugin.getMarketService().getMarketData().getRarities().put(id, new RarityDefinition(
                        id,
                        "#03fc88New Rarity",
                        new ItemStack(Material.NETHER_STAR)
                    ));
                    plugin.getMarketService().persistMarketDataWithoutRestock();
                    plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", id));
                    runForViewer(() -> plugin.getGuiManager().openRarityEditor(viewer, id));
                    return;
                }
                runForViewer(() -> plugin.getGuiManager().openRarityList(viewer, page));
            }, () -> {
                plugin.getMessages().send(viewer, "messages.editor-cancelled");
                plugin.getGuiManager().openRarityList(viewer, page);
            });
            return;
        }
        if (slot == NEXT_SLOT) {
            if ((page + 1) * PAGE_SIZE < rarities.size()) {
                plugin.getGuiManager().openRarityList(viewer, page + 1);
            }
        }
    }

    private int maxPage(int totalEntries) {
        return Math.max(0, (Math.max(0, totalEntries) - 1) / PAGE_SIZE);
    }

    private int entryIndex(int slot) {
        for (int index = 0; index < ENTRY_SLOTS.length; index++) {
            if (ENTRY_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private ItemStack createRarityItem(RarityDefinition rarity) {
        ItemStack item = rarity.getIcon();
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.NETHER_STAR);
        } else {
            item = item.clone();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(
                "§fID: §a" + rarity.getId(),
                "§fName: " + rarity.getDisplayName(),
                "§aClick to edit."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack emptyRaritySlot() {
        return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ");
    }
}
