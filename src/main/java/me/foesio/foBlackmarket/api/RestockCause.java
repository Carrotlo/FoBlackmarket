package me.foesio.foBlackmarket.api;

/** Source of a completed runtime restock. Restoring saved state on startup is not a restock. */
public enum RestockCause {
    SCHEDULED, ADMIN, EDITOR, API, DIRECT
}
