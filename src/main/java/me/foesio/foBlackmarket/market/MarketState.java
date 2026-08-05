package me.foesio.foBlackmarket.market;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record MarketState(
    long restockedAt,
    long nextRestockAt,
    Map<String, OfferState> offers,
    Map<Integer, String> slotRewardIds
) {

    public MarketState {
        offers = offers == null ? Map.of() : new LinkedHashMap<>(offers);
        slotRewardIds = slotRewardIds == null ? Map.of() : new LinkedHashMap<>(slotRewardIds);
    }

    public record OfferState(
        String rewardId,
        int remainingStock,
        Map<UUID, Integer> perPlayerRemainingStock
    ) {

        public OfferState {
            rewardId = rewardId == null ? "" : rewardId;
            remainingStock = Math.max(0, remainingStock);
            perPlayerRemainingStock = perPlayerRemainingStock == null ? Map.of() : new LinkedHashMap<>(perPlayerRemainingStock);
        }
    }
}
