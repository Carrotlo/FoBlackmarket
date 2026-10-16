package me.foesio.foBlackmarket.market;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MarketRepositoryValidationTest {

    @TempDir
    Path temporaryFolder;

    @Test
    void stockSavePreservesUnreloadedOwnerEditsAndRejectsMalformedSource() throws Exception {
        Path file = temporaryFolder.resolve("market.yml");
        String ownerSource = "# owner's market\nrarities:\n  custom:\n    name: 'Émeraude'\n"
            + "rewards:\n  special:\n    icon:\n      custom-field: kept\n"
            + "state:\n  restocked-at: 1\n  next-restock-at: 2\n";
        Files.writeString(file, ownerSource, StandardCharsets.UTF_8);
        MarketSnapshot snapshot = new MarketSnapshot(Map.of(), Map.of(), 3L, 4L);

        MarketRepository.saveState(file.toFile(), snapshot);
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("# owner's market"));
        assertTrue(saved.contains("Émeraude"));
        assertTrue(saved.contains("custom-field: kept"));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(saved);
        assertEquals(3L, yaml.getLong("state.restocked-at"));
        assertEquals(4L, yaml.getLong("state.next-restock-at"));

        String malformed = "rewards: [unterminated\n";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> MarketRepository.saveState(file.toFile(), snapshot));
        assertEquals(malformed, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void editorSaveRetainsUnmanagedFieldsAndDoesNotReplaceMalformedSource() throws Exception {
        Path file = temporaryFolder.resolve("market.yml");
        String ownerSource = "# owner's market\ncustom-setting: kept\ngui:\n  extra: owner\n"
            + "  slot-rarities:\n    '20': old\nrarities:\n  old:\n    name: Old\nrewards: {}\n";
        Files.writeString(file, ownerSource, StandardCharsets.UTF_8);
        MarketRepository repository = new MarketRepository(file.toFile());
        MarketData edited = new MarketData(new MarketLayout(54, 4, 49, null, Map.of(20, "custom")),
            Map.of("custom", new RarityDefinition("custom", "Émeraude", null)), Map.of());

        repository.save(edited);
        YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("kept", saved.getString("custom-setting"));
        assertEquals("owner", saved.getString("gui.extra"));
        assertEquals("custom", saved.getString("gui.slot-rarities.20"));
        assertEquals("Émeraude", saved.getString("rarities.custom.name"));
        assertTrue(!saved.contains("rarities.old"));

        String malformed = "rewards: [unterminated\n";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> repository.save(edited));
        assertEquals(malformed, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void movedOfferSlotsKeepValidRarityAssignments() throws Exception {
        YamlConfiguration market = bundledMarket();
        assertDoesNotThrow(() -> MarketRepository.validateCurrent(market, List.of(19, 21, 22, 23, 24)));
    }

    @Test
    void rejectsInvalidCurrentMarketBeforePublishingGui() throws Exception {
        YamlConfiguration market = bundledMarket();
        market.set("rewards.starter_food.stock", "many");
        YamlConfiguration invalidStock = market;
        IOException error = assertThrows(IOException.class,
            () -> MarketRepository.validateCurrent(invalidStock, List.of(20, 21, 22, 23, 24)));
        assertTrue(error.getMessage().contains("rewards.starter_food.stock"));

        market = bundledMarket();
        market.set("gui.slot-rarities.20", "missing");
        YamlConfiguration invalidRarity = market;
        error = assertThrows(IOException.class,
            () -> MarketRepository.validateCurrent(invalidRarity, List.of(20, 21, 22, 23, 24)));
        assertTrue(error.getMessage().contains("gui.slot-rarities.20"));
    }

    @Test
    void emptyAssignmentsAndRemovedRaritiesCanRemainEmpty() throws Exception {
        YamlConfiguration market = bundledMarket();
        market.set("gui.slot-rarities", null);
        market.set("rarities", null);
        market.createSection("rarities");
        market.set("rewards", null);
        market.createSection("rewards");
        assertDoesNotThrow(() -> MarketRepository.validateCurrent(market, List.of(20, 21, 22, 23, 24)));
    }

    @Test
    void malformedHeadSkinCannotReplaceCurrentMarket() throws Exception {
        YamlConfiguration market = bundledMarket();
        market.set("rewards.starter_food.icon.material", "PLAYER_HEAD");
        market.set("rewards.starter_food.icon.skull.textures.skin", "not-a-url");
        IOException problem = assertThrows(IOException.class,
            () -> MarketRepository.validateCurrent(market, List.of(20, 21, 22, 23, 24)));
        assertTrue(problem.getMessage().contains("skull.textures.skin"));
        market.set("rewards.starter_food.icon.skull.textures.skin",
            "https://textures.minecraft.net/texture/abc123");
        assertDoesNotThrow(() -> MarketRepository.validateCurrent(market, List.of(20, 21, 22, 23, 24)));
    }

    private static YamlConfiguration bundledMarket() throws Exception {
        try (InputStream stream = MarketRepositoryValidationTest.class.getClassLoader()
            .getResourceAsStream("market.yml")) {
            if (stream == null) throw new IllegalStateException("Missing market.yml");
            YamlConfiguration market = new YamlConfiguration();
            market.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return market;
        }
    }
}
