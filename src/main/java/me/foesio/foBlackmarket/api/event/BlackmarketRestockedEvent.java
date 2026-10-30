package me.foesio.foBlackmarket.api.event;

import java.util.Objects;
import me.foesio.foBlackmarket.api.RestockCause;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread after a runtime restock has been saved. */
public final class BlackmarketRestockedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final RestockCause cause;
    private final long restockedAt;
    private final long nextRestockAt;

    public BlackmarketRestockedEvent(RestockCause cause, long restockedAt, long nextRestockAt) {
        super(false);
        this.cause = Objects.requireNonNull(cause, "cause");
        this.restockedAt = restockedAt;
        this.nextRestockAt = nextRestockAt;
    }

    public RestockCause getCause() { return cause; }
    public long getRestockedAt() { return restockedAt; }
    public long getNextRestockAt() { return nextRestockAt; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
