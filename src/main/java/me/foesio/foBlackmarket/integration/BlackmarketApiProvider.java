package me.foesio.foBlackmarket.integration;

import java.util.Optional;
import java.util.UUID;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.api.BlackmarketApi;
import me.foesio.foBlackmarket.api.MarketView;
import me.foesio.foBlackmarket.api.OpenMarketResult;
import me.foesio.foBlackmarket.api.PurchaseQuote;
import me.foesio.foBlackmarket.api.RestockCause;
import me.foesio.foBlackmarket.api.RestockResult;
import me.foesio.foBlackmarket.gui.GuiManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Keeps the stable service boundary separate from mutable market and GUI internals. */
public final class BlackmarketApiProvider implements BlackmarketApi {

    private final FoBlackmarket plugin;

    public BlackmarketApiProvider(FoBlackmarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public Optional<MarketView> currentMarket(UUID playerId) {
        requireServerThread();
        if (!plugin.isEnabled() || playerId == null) return Optional.empty();
        return plugin.getMarketService().currentMarket(playerId);
    }

    @Override
    public PurchaseQuote quotePurchase(Player player, String offerId) {
        requireServerThread();
        if (!plugin.isEnabled() || player == null || !player.isOnline()) {
            return new PurchaseQuote(PurchaseQuote.Status.UNAVAILABLE, offerId, 0, "");
        }
        return plugin.getMarketService().quotePurchase(player, offerId);
    }

    @Override
    public RestockResult requestRestock() {
        requireServerThread();
        if (!plugin.isEnabled() || !plugin.getMarketService().hasActiveMarket()) return RestockResult.UNAVAILABLE;
        try {
            plugin.getMarketService().restockNow(true, RestockCause.API);
            return RestockResult.RESTOCKED;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("API restock failed: " + exception.getMessage());
            return RestockResult.FAILED;
        }
    }

    @Override
    public OpenMarketResult openPlayerMarket(Player player) {
        requireServerThread();
        if (player == null || !player.isOnline()) return OpenMarketResult.PLAYER_OFFLINE;
        if (!plugin.isEnabled() || !plugin.getMarketService().hasActiveMarket()) return OpenMarketResult.UNAVAILABLE;
        GuiManager.OpenPlayerMarketResult result = plugin.getGuiManager().openPlayerMarket(player);
        return switch (result) {
            case OPENED -> OpenMarketResult.OPENED;
            case ALREADY_OPEN -> OpenMarketResult.ALREADY_OPEN;
            case COOLDOWN -> OpenMarketResult.COOLDOWN;
            case UNAVAILABLE -> OpenMarketResult.UNAVAILABLE;
        };
    }

    private void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("FoBlackmarket API calls require the Bukkit server thread");
        }
    }
}
