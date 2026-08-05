package me.foesio.foBlackmarket.gui;

import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class GuiListener implements Listener {

    private final FoBlackmarket plugin;
    private final GuiManager guiManager;

    public GuiListener(FoBlackmarket plugin, GuiManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BaseGui gui)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean topClick = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;
        boolean cancel = topClick
            || !gui.allowsPlayerInventoryInteraction()
            || event.isShiftClick()
            || event.getClick() == ClickType.NUMBER_KEY
            || event.getClick() == ClickType.DOUBLE_CLICK;

        event.setCancelled(cancel);
        gui.handleClick(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BaseGui gui)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesTop = false;
        for (Integer slot : event.getRawSlots()) {
            if (slot < topSize) {
                touchesTop = true;
                break;
            }
        }
        event.setCancelled(touchesTop || !gui.allowsPlayerInventoryInteraction());
        gui.handleDrag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
            && plugin.getCore() != null
            && plugin.getCore().inventoryCloseSuppressor().consumeSuppressedClose(player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof BaseGui gui) {
            gui.handleClose(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.getCore() == null) {
            return;
        }
        plugin.getCore().inventoryCloseSuppressor().clear(event.getPlayer());
        plugin.getCore().dialogInputs().clear(event.getPlayer());
    }
}
