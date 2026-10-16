package me.foesio.foBlackmarket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class PublicMessageValidationTest {

    @Test
    void optionalRemovedLinesRemainAbsent() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("tokens.prefix", "Mijn Markt");
        messages.set("messages.market-bought", null);
        assertDoesNotThrow(() -> FoBlackmarket.validatePublicMessageTypes(messages));
    }

    @Test
    void invalidPublicMessageCannotReplaceWorkingCache() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("messages.market-bought", java.util.List.of("wrong type"));
        IllegalStateException problem = assertThrows(IllegalStateException.class,
            () -> FoBlackmarket.validatePublicMessageTypes(messages));
        assertTrue(problem.getMessage().contains("messages.market-bought"));
    }

    @Test
    void invalidNumberLocaleIsReported() {
        YamlConfiguration messages = new YamlConfiguration();
        messages.set("gui.number-locale", "en_US_bad_!");
        IllegalStateException problem = assertThrows(IllegalStateException.class,
            () -> FoBlackmarket.validatePublicMessageTypes(messages));
        assertTrue(problem.getMessage().contains("gui.number-locale"));
    }
}
