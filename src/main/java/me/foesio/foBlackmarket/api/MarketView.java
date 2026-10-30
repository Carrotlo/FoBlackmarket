package me.foesio.foBlackmarket.api;

import java.util.List;
import java.util.Objects;

/** A copy of the active player market at one instant. Times are Unix epoch milliseconds. */
public record MarketView(long restockedAt, long nextRestockAt, List<OfferView> offers) {

    public MarketView {
        offers = List.copyOf(Objects.requireNonNull(offers, "offers"));
    }
}
