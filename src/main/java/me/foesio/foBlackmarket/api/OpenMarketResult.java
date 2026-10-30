package me.foesio.foBlackmarket.api;

/** Outcome of opening the player buying market for an online player. */
public enum OpenMarketResult {
    OPENED, ALREADY_OPEN, COOLDOWN, UNAVAILABLE, PLAYER_OFFLINE
}
