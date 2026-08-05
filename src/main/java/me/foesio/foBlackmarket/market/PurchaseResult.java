package me.foesio.foBlackmarket.market;

public final class PurchaseResult {

    public enum Status {
        SUCCESS,
        UNAVAILABLE,
        SOLD_OUT,
        NOT_ENOUGH_MONEY,
        NOT_ENOUGH_ITEMS,
        INVENTORY_FULL,
        FAILED
    }

    private final Status status;
    private final String rewardName;
    private final int remainingStock;
    private final String priceText;

    private PurchaseResult(Status status, String rewardName, int remainingStock, String priceText) {
        this.status = status;
        this.rewardName = rewardName;
        this.remainingStock = remainingStock;
        this.priceText = priceText;
    }

    public static PurchaseResult success(String rewardName, int remainingStock) {
        return new PurchaseResult(Status.SUCCESS, rewardName, remainingStock, "");
    }

    public static PurchaseResult of(Status status) {
        return new PurchaseResult(status, "", 0, "");
    }

    public static PurchaseResult moneyFailure(String priceText) {
        return new PurchaseResult(Status.NOT_ENOUGH_MONEY, "", 0, priceText);
    }

    public Status getStatus() {
        return status;
    }

    public String getRewardName() {
        return rewardName;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public String getPriceText() {
        return priceText;
    }
}
