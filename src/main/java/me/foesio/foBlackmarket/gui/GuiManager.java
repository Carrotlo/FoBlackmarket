package me.foesio.foBlackmarket.gui;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class GuiManager {

    public enum OpenPlayerMarketResult {
        OPENED,
        ALREADY_OPEN,
        COOLDOWN
    }

    private static final String OPEN_GUI_BUCKET = "market-open";
    private final FoBlackmarket plugin;
    private final Map<UUID, PlayerMarketGui> openPlayerMarketGuis = new HashMap<>();
    private final Map<UUID, CommandMarketGui> openCommandMarketGuis = new HashMap<>();

    public GuiManager(FoBlackmarket plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPlayerMarketGuis, 20L, 20L);
    }

    public OpenPlayerMarketResult openPlayerMarket(Player target) {
        if (!plugin.getActionCooldowns().tryAcquire(target.getUniqueId(), OPEN_GUI_BUCKET, plugin.getConfigManager().getOpenGuiCooldownMillis())) {
            return OpenPlayerMarketResult.COOLDOWN;
        }

        PlayerMarketGui currentGui = openPlayerMarketGuis.get(target.getUniqueId());
        int expectedPlayerMarketSize = plugin.getMarketService().getMarketData().getLayout().getSize();
        if (currentGui != null) {
            if (target.getOpenInventory().getTopInventory().getHolder() == currentGui
                && currentGui.getInventory().getSize() == expectedPlayerMarketSize) {
                return OpenPlayerMarketResult.ALREADY_OPEN;
            }
        }

        PlayerMarketGui gui = new PlayerMarketGui(plugin, target, this);
        openPlayerMarketGuis.put(target.getUniqueId(), gui);
        gui.open();
        return OpenPlayerMarketResult.OPENED;
    }

    public OpenPlayerMarketResult openCommandMarket(Player target) {
        if (!plugin.getActionCooldowns().tryAcquire(target.getUniqueId(), OPEN_GUI_BUCKET, plugin.getConfigManager().getOpenGuiCooldownMillis())) {
            return OpenPlayerMarketResult.COOLDOWN;
        }

        CommandMarketGui currentGui = openCommandMarketGuis.get(target.getUniqueId());
        if (currentGui != null) {
            if (target.getOpenInventory().getTopInventory().getHolder() == currentGui
                && currentGui.getInventory().getSize() == plugin.getGuiConfigManager().commandMarket().size()) {
                return OpenPlayerMarketResult.ALREADY_OPEN;
            }
        }

        CommandMarketGui gui = new CommandMarketGui(plugin, target, this);
        openCommandMarketGuis.put(target.getUniqueId(), gui);
        gui.open();
        return OpenPlayerMarketResult.OPENED;
    }

    public void openMainEditor(Player player) {
        new MainEditorGui(plugin, player).open();
    }

    public void openRewardList(Player player, int page) {
        new RewardListGui(plugin, player, page).open();
    }

    public void openRewardEditor(Player player, String rewardId) {
        new RewardEditGui(plugin, player, rewardId).open();
    }

    public void openRarityList(Player player, int page) {
        new RarityListGui(plugin, player, page).open();
    }

    public void openRarityEditor(Player player, String rarityId) {
        new RarityEditGui(plugin, player, rarityId).open();
    }

    public void openLayoutEditor(Player player) {
        new LayoutEditorGui(plugin, player).open();
    }

    public void refreshPlayerMarketGuis() {
        if (plugin.getMarketService() == null) {
            return;
        }
        Iterator<Map.Entry<UUID, PlayerMarketGui>> iterator = openPlayerMarketGuis.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerMarketGui> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            PlayerMarketGui gui = entry.getValue();
            if (player == null || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                iterator.remove();
                continue;
            }
            if (gui.getInventory().getSize() != plugin.getMarketService().getMarketData().getLayout().getSize()) {
                PlayerMarketGui replacement = new PlayerMarketGui(plugin, player, this);
                entry.setValue(replacement);
                replacement.open();
                continue;
            }
            gui.refresh();
        }

        Iterator<Map.Entry<UUID, CommandMarketGui>> commandIterator = openCommandMarketGuis.entrySet().iterator();
        while (commandIterator.hasNext()) {
            Map.Entry<UUID, CommandMarketGui> entry = commandIterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            CommandMarketGui gui = entry.getValue();
            if (player == null || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                commandIterator.remove();
                continue;
            }
            if (gui.getInventory().getSize() != plugin.getGuiConfigManager().commandMarket().size()) {
                CommandMarketGui replacement = new CommandMarketGui(plugin, player, this);
                entry.setValue(replacement);
                replacement.open();
                continue;
            }
            gui.refresh();
        }
    }

    public void refreshPlayerMarketOffer(me.foesio.foBlackmarket.market.ActiveOffer offer) {
        if (offer == null) {
            return;
        }
        Iterator<Map.Entry<UUID, PlayerMarketGui>> iterator = openPlayerMarketGuis.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerMarketGui> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            PlayerMarketGui gui = entry.getValue();
            if (player == null || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                iterator.remove();
                continue;
            }
            if (gui.getInventory().getSize() != plugin.getMarketService().getMarketData().getLayout().getSize()) {
                PlayerMarketGui replacement = new PlayerMarketGui(plugin, player, this);
                entry.setValue(replacement);
                replacement.open();
                continue;
            }
            gui.refreshOffer(offer);
        }

        Iterator<Map.Entry<UUID, CommandMarketGui>> commandIterator = openCommandMarketGuis.entrySet().iterator();
        while (commandIterator.hasNext()) {
            Map.Entry<UUID, CommandMarketGui> entry = commandIterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            CommandMarketGui gui = entry.getValue();
            if (player == null || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                commandIterator.remove();
                continue;
            }
            if (gui.getInventory().getSize() != plugin.getGuiConfigManager().commandMarket().size()) {
                CommandMarketGui replacement = new CommandMarketGui(plugin, player, this);
                entry.setValue(replacement);
                replacement.open();
                continue;
            }
            gui.refresh();
        }
    }

    void unregisterPlayerMarketGui(Player player, PlayerMarketGui gui) {
        openPlayerMarketGuis.remove(player.getUniqueId(), gui);
    }

    void unregisterCommandMarketGui(Player player, CommandMarketGui gui) {
        openCommandMarketGuis.remove(player.getUniqueId(), gui);
    }
}
