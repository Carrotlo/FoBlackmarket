package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.gui.FoButtonStyle;
import me.foesio.core.message.FoStyle;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.ActiveOffer;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class CommandMarketGui extends BaseGui {

    private final GuiManager guiManager;
    private final GuiConfigManager.CommandMarketGuiConfig guiConfig;
    private final Map<Integer, String> rarityIdsBySlot = new HashMap<>();
    private final Map<Integer, String> offerIdsBySlot = new HashMap<>();
    private final Map<String, Integer> offerPagesByRarity = new HashMap<>();
    private String selectedRarityId;

    public CommandMarketGui(FoBlackmarket plugin, Player viewer, GuiManager guiManager) {
        super(plugin, viewer, plugin.getGuiConfigManager().commandMarket().size(), plugin.getGuiConfigManager().commandMarket().title());
        this.guiManager = guiManager;
        this.guiConfig = plugin.getGuiConfigManager().commandMarket();
        this.selectedRarityId = plugin.getMarketService().getDefaultRarityId();
    }

    @Override
    protected void openSound() {
        plugin.getGuiSounds().open(viewer);
    }

    @Override
    protected void render() {
        fill(plugin.getMarketService().getMarketData().getLayout().getFillerItem());
        rarityIdsBySlot.clear();
        offerIdsBySlot.clear();
        selectedRarityId = resolveSelectedRarityId();

        List<ActiveOffer> selectedOffers = plugin.getMarketService().getOffersForRarity(selectedRarityId);
        int offerPage = getClampedOfferPage(selectedRarityId, selectedOffers.size());
        int maxPage = maxOfferPage(selectedOffers.size());
        int offerStart = offerPage * guiConfig.offerPageSize();
        int offerCount = Math.min(guiConfig.offerPageSize(), Math.max(0, selectedOffers.size() - offerStart));

        inventory.setItem(guiConfig.timerSlot(), guiConfig.timerItem().toItem(plugin.getMessages(), viewer, Map.of(
            "time_remaining", plugin.getConfigManager().formatDuration(plugin.getMarketService().getTimeUntilRestock()),
            "selected_rarity", getSelectedRarityLabel(),
            "showing_offers", getShowingOffersLine(offerStart, offerCount, selectedOffers.size())
        )));

        List<RarityDefinition> rarities = plugin.getMarketService().getRarities();
        int[] raritySlots = guiConfig.raritySlots();
        int rarityCount = Math.min(rarities.size(), raritySlots.length);
        for (int index = 0; index < rarityCount; index++) {
            RarityDefinition rarity = rarities.get(index);
            int slot = raritySlots[index];
            rarityIdsBySlot.put(slot, rarity.getId());
            inventory.setItem(slot, createRarityItem(rarity, selectedOffersFor(rarity.getId()).size(), rarity.getId().equals(selectedRarityId)));
        }

        int[] offerSlots = guiConfig.offerSlots();
        for (int index = 0; index < offerCount; index++) {
            ActiveOffer offer = selectedOffers.get(offerStart + index);
            int slot = offerSlots[index];
            offerIdsBySlot.put(slot, offer.getRewardId());
            inventory.setItem(slot, createOfferItem(offer));
        }

        for (int index = offerCount; index < offerSlots.length; index++) {
            inventory.setItem(offerSlots[index], guiConfig.emptyOfferItem().toItem(plugin.getMessages(), viewer, Map.of()));
        }

        if (selectedOffers.size() > guiConfig.offerPageSize()) {
            if (offerPage > 0) {
                inventory.setItem(guiConfig.previousPageSlot(), buttons.previousPage(viewer, offerPage, maxPage));
            }
            if ((offerPage + 1) * guiConfig.offerPageSize() < selectedOffers.size()) {
                inventory.setItem(guiConfig.nextPageSlot(), buttons.nextPage(viewer, offerPage, maxPage));
            }
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        String rarityId = rarityIdsBySlot.get(slot);
        if (rarityId != null) {
            if (rarityId.equals(selectedRarityId)) {
                return;
            }
            selectedRarityId = rarityId;
            refresh();
            plugin.getGuiSounds().select(viewer);
            return;
        }
        if (slot == guiConfig.timerSlot()) {
            return;
        }

        if (slot == guiConfig.previousPageSlot() || slot == guiConfig.nextPageSlot()) {
            List<ActiveOffer> selectedOffers = plugin.getMarketService().getOffersForRarity(selectedRarityId);
            int currentPage = getClampedOfferPage(selectedRarityId, selectedOffers.size());
            int maxPage = maxOfferPage(selectedOffers.size());
            if (slot == guiConfig.previousPageSlot() && currentPage > 0) {
                setOfferPage(selectedRarityId, currentPage - 1);
                refresh();
                plugin.getGuiSounds().previousPage(viewer);
            } else if (slot == guiConfig.nextPageSlot() && currentPage < maxPage) {
                setOfferPage(selectedRarityId, currentPage + 1);
                refresh();
                plugin.getGuiSounds().nextPage(viewer);
            }
            return;
        }

        if (offerIdsBySlot.containsKey(slot)) {
            return;
        }
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        guiManager.unregisterCommandMarketGui(viewer, this);
    }

    private ItemStack createOfferItem(ActiveOffer offer) {
        if (offer == null) {
            return null;
        }

        ItemStack preview = plugin.getMarketService().createOfferDisplayItem(offer, viewer);
        if (preview == null) {
            return null;
        }

        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            String previewLine = plugin.getMessages().renderTemplateForViewer(
                    viewer,
                    guiConfig.offerPreviewLore(),
                    Map.of()
            );
            if (lore.isEmpty()) {
                lore.add(previewLine);
            } else {
                lore.set(lore.size() - 1, FoButtonStyle.informationLine(previewLine));
            }
            // FoButtonStyle uses editable legacy color tokens. Re-apply the
            // complete metadata through Core so &7/&f and any configured
            // sprite tokens are rendered for this viewer instead of leaking as
            // literal text.
            if (!DialogIcons.applyItemMeta(meta, viewer, meta.getDisplayName(), lore)) {
                meta.setDisplayName(DialogIcons.fallbackText(meta.getDisplayName()));
                meta.setLore(lore.stream().map(DialogIcons::fallbackText).toList());
            }
            preview.setItemMeta(meta);
        }
        return preview;
    }

    private ItemStack createRarityItem(RarityDefinition rarity, int offerCount, boolean selected) {
        ItemStack base = rarity.getIcon();
        ItemStack icon = base == null || base.getType().isAir() ? new ItemStack(Material.NAME_TAG) : base.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            String label = plugin.getMessages().renderTemplateForViewer(viewer, rarity.getDisplayName(), Map.of());
            Map<String, String> placeholders = Map.of(
                "rarity", rarity.getDisplayName(),
                "rarity_id", rarity.getId(),
                "offer_count", String.valueOf(offerCount),
                "selection_status", selected ? guiConfig.raritySelectedLine() : guiConfig.rarityUnselectedLine()
            );
            List<String> information = new ArrayList<>();
            for (String line : guiConfig.rarityLore()) {
                String rendered = plugin.getMessages().renderTemplateForViewer(viewer, line, placeholders);
                if (!rendered.isBlank()) {
                    information.add(rendered);
                }
            }
            String buttonName = DialogIcons.withMaterialIcon(
                    FoButtonStyle.buttonName(FoStyle.THEME, label),
                    icon.getType()
            );
            List<String> lore = FoButtonStyle.buttonLore(
                    information,
                    "view " + FoText.plain(label)
            );
            if (!DialogIcons.applyItemMeta(meta, viewer, buttonName, lore)) {
                meta.setDisplayName(FoText.color(DialogIcons.fallbackText(buttonName)));
                meta.setLore(FoText.color(lore.stream().map(DialogIcons::fallbackText).toList()));
            }
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private String resolveSelectedRarityId() {
        if (selectedRarityId != null && !selectedOffersFor(selectedRarityId).isEmpty()) {
            return selectedRarityId;
        }
        String defaultRarityId = plugin.getMarketService().getDefaultRarityId();
        if (defaultRarityId != null) {
            return defaultRarityId;
        }
        return selectedRarityId;
    }

    private String getSelectedRarityLabel() {
        for (RarityDefinition rarity : plugin.getMarketService().getRarities()) {
            if (rarity.getId().equals(selectedRarityId)) {
                return rarity.getDisplayName();
            }
        }
        return "#03fc88None";
    }

    private List<ActiveOffer> selectedOffersFor(String rarityId) {
        return plugin.getMarketService().getOffersForRarity(rarityId);
    }

    private int getClampedOfferPage(String rarityId, int offerCount) {
        if (rarityId == null || rarityId.isBlank()) {
            return 0;
        }
        int page = offerPagesByRarity.getOrDefault(rarityId, 0);
        int clampedPage = Math.max(0, Math.min(page, maxOfferPage(offerCount)));
        if (clampedPage != page) {
            offerPagesByRarity.put(rarityId, clampedPage);
        }
        return clampedPage;
    }

    private void setOfferPage(String rarityId, int page) {
        if (rarityId == null || rarityId.isBlank()) {
            return;
        }
        offerPagesByRarity.put(rarityId, Math.max(0, page));
    }

    private int maxOfferPage(int offerCount) {
        return Math.max(0, (offerCount - 1) / guiConfig.offerPageSize());
    }

    private String getShowingOffersLine(int offerStart, int offerCount, int totalOffers) {
        if (totalOffers <= 0) {
            return "#a7b8b0Showing 0 offers.";
        }
        return "#a7b8b0Showing " + (offerStart + 1) + "-" + (offerStart + offerCount) + " of " + totalOffers + " offers.";
    }
}
