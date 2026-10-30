package me.foesio.foBlackmarket.api;

import java.util.Objects;

/** A best-effort eligibility check; another purchase or restock can invalidate it immediately. */
public record PurchaseQuote(Status status, String offerId, int remainingStock, String priceText) {

    public PurchaseQuote {
        status = Objects.requireNonNull(status, "status");
        offerId = offerId == null ? "" : offerId;
        remainingStock = Math.max(0, remainingStock);
        priceText = priceText == null ? "" : priceText;
    }

    public enum Status {
        AVAILABLE, UNAVAILABLE, SOLD_OUT, INVENTORY_FULL, NOT_ENOUGH_MONEY, NOT_ENOUGH_ITEMS, FAILED
    }

    public boolean canPurchase() {
        return status == Status.AVAILABLE;
    }
}
