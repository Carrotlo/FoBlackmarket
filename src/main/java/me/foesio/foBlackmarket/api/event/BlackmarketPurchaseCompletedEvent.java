package me.foesio.foBlackmarket.api.event;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread after payment, item delivery, and stock persistence succeed,
 * before configured reward commands and GUI refresh. */
public final class BlackmarketPurchaseCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String offerId;
    private final int remainingStock;

    public BlackmarketPurchaseCompletedEvent(Player player, String offerId, int remainingStock) {
        super(false);
        this.player = Objects.requireNonNull(player, "player");
        this.offerId = Objects.requireNonNull(offerId, "offerId");
        this.remainingStock = remainingStock;
    }

    public Player getPlayer() { return player; }
    public String getOfferId() { return offerId; }
    public int getRemainingStock() { return remainingStock; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
