package me.foesio.foBlackmarket.market;

import java.util.Locale;

public enum StockMode {
    SERVER_WIDE("Server-wide"),
    PER_PLAYER("Per-player");

    private final String displayName;

    StockMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public StockMode next() {
        return this == SERVER_WIDE ? PER_PLAYER : SERVER_WIDE;
    }

    public static StockMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SERVER_WIDE;
        }
        String normalized = raw.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return StockMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return SERVER_WIDE;
        }
    }
}
