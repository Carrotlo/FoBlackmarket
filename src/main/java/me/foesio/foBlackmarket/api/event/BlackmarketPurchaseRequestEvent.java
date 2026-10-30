package me.foesio.foBlackmarket.api.event;

import java.util.Objects;
import me.foesio.foBlackmarket.api.PurchaseQuote;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread after eligibility checks, before any payment or reward. */
public final class BlackmarketPurchaseRequestEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final PurchaseQuote quote;
    private boolean cancelled;

    public BlackmarketPurchaseRequestEvent(Player player, PurchaseQuote quote) {
        super(false);
        this.player = Objects.requireNonNull(player, "player");
        this.quote = Objects.requireNonNull(quote, "quote");
    }

    public Player getPlayer() { return player; }
    public String getOfferId() { return quote.offerId(); }
    public PurchaseQuote getQuote() { return quote; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
