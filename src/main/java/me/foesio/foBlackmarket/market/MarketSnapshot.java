package me.foesio.foBlackmarket.market;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MarketSnapshot {

    private final Map<String, ActiveOffer> offersByRewardId;
    private final Map<Integer, ActiveOffer> offersBySlot;
    private final long restockedAt;
    private final long nextRestockAt;

    public MarketSnapshot(
        Map<String, ActiveOffer> offersByRewardId,
        Map<Integer, ActiveOffer> offersBySlot,
        long restockedAt,
        long nextRestockAt
    ) {
        this.offersByRewardId = new LinkedHashMap<>(offersByRewardId);
        this.offersBySlot = new LinkedHashMap<>(offersBySlot);
        this.restockedAt = restockedAt;
        this.nextRestockAt = nextRestockAt;
    }

    public Map<String, ActiveOffer> getOffersByRewardId() {
        return offersByRewardId;
    }

    public Map<Integer, ActiveOffer> getOffersBySlot() {
        return offersBySlot;
    }

    public long getRestockedAt() {
        return restockedAt;
    }

    public long getNextRestockAt() {
        return nextRestockAt;
    }
}
