package me.foesio.foBlackmarket.api;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Public FoBlackmarket integration service. Obtain it through Bukkit's ServicesManager. */
public interface BlackmarketApi {

    /** Returns the currently displayed buying offers with stock for this player, or empty while unavailable. */
    Optional<MarketView> currentMarket(UUID playerId);

    /** Checks current purchase requirements without reserving stock or changing player state. */
    PurchaseQuote quotePurchase(Player player, String offerId);

    /** Rerolls and persists the market, using the same announcement behavior as an admin restock. */
    RestockResult requestRestock();

    /** Opens the buying menu using its normal availability and cooldown checks. */
    OpenMarketResult openPlayerMarket(Player player);
}
