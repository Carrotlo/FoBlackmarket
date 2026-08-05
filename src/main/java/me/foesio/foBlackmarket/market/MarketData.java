package me.foesio.foBlackmarket.market;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MarketData {

    private MarketLayout layout;
    private Map<String, RarityDefinition> rarities;
    private Map<String, RewardDefinition> rewards;

    public MarketData(MarketLayout layout, Map<String, RarityDefinition> rarities, Map<String, RewardDefinition> rewards) {
        this.layout = layout;
        this.rarities = new LinkedHashMap<>(rarities);
        this.rewards = new LinkedHashMap<>(rewards);
    }

    public MarketLayout getLayout() {
        return layout;
    }

    public void setLayout(MarketLayout layout) {
        this.layout = layout;
    }

    public Map<String, RarityDefinition> getRarities() {
        return rarities;
    }

    public Map<String, RewardDefinition> getRewards() {
        return rewards;
    }
}
