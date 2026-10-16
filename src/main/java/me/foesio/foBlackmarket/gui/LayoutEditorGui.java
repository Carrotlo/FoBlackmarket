package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Edits rarity assignments for the offer slots declared by the public GUI YAML. */
public final class LayoutEditorGui extends BaseGui {

    private static final int PAGE_SIZE = 45;
    private final int page;

    public LayoutEditorGui(FoBlackmarket plugin, Player viewer, int page) {
        super(plugin, viewer, 54, plugin.title("layout"));
        this.page = Math.max(0, page);
    }

    @Override
    protected void render() {
        List<Integer> slots = configuredSlots();
        List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
        Map<Integer, String> assigned = plugin.getMarketService().getMarketData().getLayout().getSlotRarities();
        for (int index = 0; index < PAGE_SIZE && page * PAGE_SIZE + index < slots.size(); index++) {
            int slot = slots.get(page * PAGE_SIZE + index);
            String rarityId = assigned.get(slot);
            RarityDefinition rarity = rarityId == null ? null
                : plugin.getMarketService().getMarketData().getRarities().get(rarityId);
            inventory.setItem(index, assignmentButton(slot, rarityIds, rarityId, rarity));
        }
        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW, "#03fc88", "Previous Page",
                List.of("Show earlier offer slots."), "view previous page"));
        }
        if ((page + 1) * PAGE_SIZE < slots.size()) {
            inventory.setItem(53, button(Material.ARROW, "#03fc88", "Next Page",
                List.of("Show more offer slots."), "view next page"));
        }
        inventory.setItem(49, buttons.back(viewer));
        inventory.setItem(52, item(Material.BOOK, "#03fc88Public GUI Layout",
            "#ffffffClick an offer slot to cycle its rarity; right click to clear.",
            "#ffffffEdit rows, offer slots, filler, and item appearance in",
            "#03fc88guis/player-market.yml #ffffffthen reload the plugin."));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int index = event.getRawSlot();
        if (index == 49) {
            editorSounds.back(viewer);
            plugin.getGuiManager().openMainEditorSilently(viewer);
        } else if (index == 45 && page > 0) {
            editorSounds.previousPage(viewer);
            plugin.getGuiManager().openLayoutEditorSilently(viewer, page - 1);
        } else if (index == 53 && (page + 1) * PAGE_SIZE < configuredSlots().size()) {
            editorSounds.nextPage(viewer);
            plugin.getGuiManager().openLayoutEditorSilently(viewer, page + 1);
        } else if (index >= 0 && index < PAGE_SIZE) {
            List<Integer> slots = configuredSlots();
            int offset = page * PAGE_SIZE + index;
            if (offset < slots.size()) {
                cycleSlotRarity(slots.get(offset), event.isRightClick());
            }
        }
    }

    private List<Integer> configuredSlots() {
        return plugin.getGuiManager().publicMenus().playerOfferSlots();
    }

    private void cycleSlotRarity(int slot, boolean clear) {
        Map<Integer, String> assigned = plugin.getMarketService().getMarketData().getLayout().getSlotRarities();
        if (clear) {
            assigned.remove(slot);
            editorSounds.delete(viewer);
        } else {
            List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
            if (rarityIds.isEmpty()) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Create a rarity first."));
                return;
            }
            String current = assigned.get(slot);
            int index = current == null ? -1 : rarityIds.indexOf(current);
            assigned.put(slot, rarityIds.get((index + 1 + rarityIds.size()) % rarityIds.size()));
            editorSounds.cycle(viewer);
        }
        plugin.getMarketService().persistMarketData(false);
        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "layout slot"));
        plugin.getGuiManager().openLayoutEditorSilently(viewer, page);
    }

    private ItemStack assignmentButton(int slot, List<String> rarityIds, String rarityId,
                                       RarityDefinition rarity) {
        List<String> lore = new ArrayList<>();
        lore.add("#ffffffPlayer market slot: #03fc88" + slot);
        lore.add(rarity == null ? "#ffffffCurrent: #a7b8b0None" : "#ffffffRarity: #03fc88" + rarity.getId());
        lore.add("#ffffffClick to cycle. Right click to clear.");
        if (!rarityIds.isEmpty()) {
            lore.addAll(CycleOptions.lore(plugin.getMessages(), rarityId,
                rarityIds.stream().map(id -> new CycleOption(id, id)).toList()));
        }
        return item(rarity == null || rarity.getIcon() == null ? Material.GRAY_STAINED_GLASS_PANE
            : rarity.getIcon().getType(), rarity == null ? "#a7b8b0Unassigned Slot" : rarity.getDisplayName(), lore);
    }
}
