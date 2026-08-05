package me.foesio.foBlackmarket.placeholder;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public final class PlaceholderApiBridge {

    private PlaceholderApiBridge() {
    }

    public static void unregister(String identifier) {
        PlaceholderAPIPlugin placeholderApi = PlaceholderAPIPlugin.getInstance();
        if (placeholderApi == null) {
            return;
        }
        placeholderApi.getLocalExpansionManager()
            .findExpansionByIdentifier(identifier)
            .ifPresent(PlaceholderExpansion::unregister);
    }
}
