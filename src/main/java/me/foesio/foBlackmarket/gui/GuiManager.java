package me.foesio.foBlackmarket.gui;

import me.foesio.core.gui.publicgui.GuiLoadReport;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.ActiveOffer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class GuiManager implements AutoCloseable {

    public enum OpenPlayerMarketResult {
        OPENED,
        ALREADY_OPEN,
        COOLDOWN,
        UNAVAILABLE
    }

    private static final String OPEN_GUI_BUCKET = "market-open";
    private final FoBlackmarket plugin;
    private final PublicMarketMenus publicMenus;

    public GuiManager(FoBlackmarket plugin) {
        this.plugin = plugin;
        this.publicMenus = new PublicMarketMenus(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, publicMenus::refreshAll, 20L, 20L);
    }

    public GuiLoadReport reloadPublicMenus() {
        return publicMenus.reload();
    }

    public PublicMarketMenus publicMenus() {
        return publicMenus;
    }

    public void forgetViewer(Player viewer) {
        publicMenus.forget(viewer);
    }

    public OpenPlayerMarketResult openPlayerMarket(Player target) {
        if (!publicMenus.available()) {
            return OpenPlayerMarketResult.UNAVAILABLE;
        }
        if (!plugin.getActionCooldowns().tryAcquire(target.getUniqueId(), OPEN_GUI_BUCKET,
            plugin.getConfigManager().getOpenGuiCooldownMillis())) {
            return OpenPlayerMarketResult.COOLDOWN;
        }
        if (publicMenus.isViewing(target, PublicMarketMenus.PLAYER)) {
            return OpenPlayerMarketResult.ALREADY_OPEN;
        }
        return publicMenus.openPlayer(target) ? OpenPlayerMarketResult.OPENED : OpenPlayerMarketResult.UNAVAILABLE;
    }

    public OpenPlayerMarketResult openCommandMarket(Player target) {
        if (!publicMenus.available()) {
            return OpenPlayerMarketResult.UNAVAILABLE;
        }
        if (!plugin.getActionCooldowns().tryAcquire(target.getUniqueId(), OPEN_GUI_BUCKET,
            plugin.getConfigManager().getOpenGuiCooldownMillis())) {
            return OpenPlayerMarketResult.COOLDOWN;
        }
        if (publicMenus.isViewing(target, PublicMarketMenus.COMMAND)) {
            return OpenPlayerMarketResult.ALREADY_OPEN;
        }
        return publicMenus.openCommand(target) ? OpenPlayerMarketResult.OPENED : OpenPlayerMarketResult.UNAVAILABLE;
    }

    public void refreshPlayerMarketGuis() {
        publicMenus.refreshAll();
    }

    public void refreshPlayerMarketOffer(ActiveOffer offer) {
        if (offer != null) {
            publicMenus.refreshAll();
        }
    }

    public void openMainEditor(Player player) {
        if (!publicMenus.available() || plugin.getMarketService().getSnapshot() == null) {
            plugin.getMessages().send(player, "messages.market-unavailable");
            return;
        }
        new MainEditorGui(plugin, player).open();
    }

    public void openMainEditorSilently(Player player) {
        new MainEditorGui(plugin, player).openSilently();
    }

    public void openRewardList(Player player, int page) {
        new RewardListGui(plugin, player, page).open();
    }

    public void openRewardListSilently(Player player, int page) {
        new RewardListGui(plugin, player, page).openSilently();
    }

    public void openRewardEditor(Player player, String rewardId) {
        new RewardEditGui(plugin, player, rewardId).open();
    }

    public void openRewardEditorSilently(Player player, String rewardId) {
        new RewardEditGui(plugin, player, rewardId).openSilently();
    }

    public void openRarityList(Player player, int page) {
        new RarityListGui(plugin, player, page).open();
    }

    public void openRarityListSilently(Player player, int page) {
        new RarityListGui(plugin, player, page).openSilently();
    }

    public void openRarityEditor(Player player, String rarityId) {
        new RarityEditGui(plugin, player, rarityId).open();
    }

    public void openRarityEditorSilently(Player player, String rarityId) {
        new RarityEditGui(plugin, player, rarityId).openSilently();
    }

    public void openLayoutEditor(Player player) {
        new LayoutEditorGui(plugin, player, 0).open();
    }

    public void openLayoutEditorSilently(Player player) {
        openLayoutEditorSilently(player, 0);
    }

    public void openLayoutEditorSilently(Player player, int page) {
        new LayoutEditorGui(plugin, player, page).openSilently();
    }

    @Override
    public void close() {
        publicMenus.close();
    }
}
