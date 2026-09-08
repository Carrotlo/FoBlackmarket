package me.foesio.foBlackmarket.gui;

import java.util.Map;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.ActiveOffer;
import me.foesio.foBlackmarket.market.MarketData;
import me.foesio.foBlackmarket.market.MarketLayout;
import me.foesio.foBlackmarket.market.MarketSnapshot;
import me.foesio.foBlackmarket.market.PurchaseResult;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class PlayerMarketGui extends BaseGui {
    private static final String PURCHASE_BUCKET = "market-purchase";
    private static final String FAILURE_FEEDBACK_BUCKET = "market-failure-feedback";
    private final GuiManager guiManager;

    public PlayerMarketGui(FoBlackmarket plugin, Player viewer, GuiManager guiManager) {
        super(plugin, viewer, plugin.getMarketService().getMarketData().getLayout().getSize(), plugin.getGuiConfigManager().playerMarket().title());
        this.guiManager = guiManager;
    }

    @Override
    protected void openSound() {
        plugin.getGuiSounds().open(viewer);
    }

    @Override
    protected void render() {
        MarketData marketData = plugin.getMarketService().getMarketData();
        MarketLayout layout = marketData.getLayout();
        GuiConfigManager.PlayerMarketGuiConfig guiConfig = plugin.getGuiConfigManager().playerMarket();
        fill(layout.getFillerItem());

        inventory.setItem(layout.getInfoSlot(), guiConfig.infoItem().toItem(plugin.getMessages(), viewer, Map.of(
            "time_remaining", plugin.getConfigManager().formatDuration(plugin.getMarketService().getTimeUntilRestock())
        )));

        MarketSnapshot snapshot = plugin.getMarketService().getSnapshot();
        if (snapshot == null) {
            return;
        }

        for (Integer slot : layout.getSlotRarities().keySet()) {
            if (slot >= inventory.getSize() || slot == layout.getInfoSlot() || slot == layout.getCloseSlot()) {
                continue;
            }
            inventory.setItem(slot, createOfferItem(snapshot.getOffersBySlot().get(slot)));
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }

        MarketLayout layout = plugin.getMarketService().getMarketData().getLayout();
        if (slot == layout.getCloseSlot()) {
            return;
        }
        if (slot == layout.getInfoSlot()) {
            return;
        }
        if (plugin.getMarketService().getOffer(slot) == null) {
            return;
        }
        if (!plugin.getActionCooldowns().tryAcquire(viewer.getUniqueId(), PURCHASE_BUCKET, plugin.getConfigManager().getPurchaseCooldownMillis())) {
            return;
        }

        PurchaseResult result = plugin.getMarketService().purchase(viewer, slot);
        switch (result.getStatus()) {
            case SUCCESS -> {
                plugin.getMessages().send(viewer, "messages.market-bought", Map.of(
                    "reward", result.getRewardName(),
                    "stock", String.valueOf(result.getRemainingStock())
                ));
                plugin.getSounds().play(viewer, "market.purchase-success");
            }
            case SOLD_OUT -> sendFailedPurchaseFeedback("market-sold-out", Map.of());
            case NOT_ENOUGH_MONEY -> sendFailedPurchaseFeedback("market-not-enough-money", Map.of("price", result.getPriceText()));
            case NOT_ENOUGH_ITEMS -> sendFailedPurchaseFeedback("market-not-enough-items", Map.of());
            case INVENTORY_FULL -> sendFailedPurchaseFeedback("market-inventory-full", Map.of());
            default -> sendFailedPurchaseFeedback("market-purchase-failed", Map.of());
        }
    }

    public void refreshOffer(ActiveOffer offer) {
        if (offer == null) {
            return;
        }
        MarketLayout layout = plugin.getMarketService().getMarketData().getLayout();
        MarketSnapshot snapshot = plugin.getMarketService().getSnapshot();
        if (snapshot == null) {
            return;
        }
        for (Map.Entry<Integer, ActiveOffer> entry : snapshot.getOffersBySlot().entrySet()) {
            if (entry.getValue() != offer) {
                continue;
            }
            int slot = entry.getKey();
            if (slot < 0 || slot >= inventory.getSize() || slot == layout.getInfoSlot() || slot == layout.getCloseSlot()) {
                continue;
            }
            ItemStack updatedItem = createOfferItem(offer);
            ItemStack currentItem = inventory.getItem(slot);
            if (itemsEqual(currentItem, updatedItem)) {
                continue;
            }
            inventory.setItem(slot, updatedItem);
        }
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        guiManager.unregisterPlayerMarketGui(viewer, this);
    }

    private ItemStack createOfferItem(ActiveOffer offer) {
        if (offer == null) {
            return null;
        }
        return plugin.getMarketService().createOfferDisplayItem(offer, viewer);
    }

    private void sendFailedPurchaseFeedback(String path, Map<String, String> placeholders) {
        if (!plugin.getActionCooldowns().tryAcquire(
            viewer.getUniqueId(),
            FAILURE_FEEDBACK_BUCKET,
            plugin.getConfigManager().getFailedPurchaseFeedbackCooldownMillis()
        )) {
            return;
        }
        plugin.getSounds().play(viewer, "market.purchase-failure");
        if (!plugin.getConfigManager().shouldSendFailedPurchaseFeedback()) {
            return;
        }
        plugin.getMessages().send(viewer, "messages." + path, placeholders);
    }
}
