package me.foesio.foBlackmarket.api;

/** An offer displayed in a player-market slot. Cost text is presentation, not a price reservation. */
public record OfferView(int slot, String offerId, String displayName, String rarityId,
                        int remainingStock, String costText) {}
