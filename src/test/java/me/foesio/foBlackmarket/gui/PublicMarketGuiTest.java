package me.foesio.foBlackmarket.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.foesio.core.gui.publicgui.ClickKind;
import me.foesio.core.gui.publicgui.GuiSnapshot;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiPlanner;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiStartupFallback;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.core.gui.publicgui.GuiDefinitionException;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicMarketGuiTest {

    @TempDir
    Path temp;

    @Test
    void generatedMenusUseCoreAndMovedOfferSlotMovesItsPurchaseBinding() throws Exception {
        YamlConfiguration player = resource("guis/player-market.yml");
        YamlConfiguration command = resource("guis/command-market.yml");
        PublicGuiDefinition playerDefault = PublicGuiSchema.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", player, PublicMarketMenus.playerContract());
        PublicGuiDefinition commandDefault = PublicGuiSchema.parse(PublicMarketMenus.COMMAND,
            "guis/command-market.yml", command, PublicMarketMenus.commandContract());
        assertEquals(5, playerDefault.rows());
        assertEquals(6, commandDefault.rows());
        assertTrue(playerDefault.buttons().isEmpty());
        assertTrue(commandDefault.buttons().isEmpty());

        player.set("entries.offers.slots", List.of(19, 21, 22, 23, 24));
        PublicGuiDefinition moved = PublicGuiSchema.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", player, PublicMarketMenus.playerContract());
        PublicGuiFrame frame = PublicGuiPlanner.plan(
            new GuiSnapshot<>(1, Map.of(PublicMarketMenus.PLAYER, moved)),
            PublicMarketMenus.PLAYER,
            new PublicGuiRenderInput(Map.of("time_remaining", "1m"), Map.of(), Map.of(),
                Map.of("offers", Map.of(19, new PublicGuiRenderInput.EntryValue(
                    "reward-one", new ItemStack(Material.CHEST), Map.of())))));
        assertEquals("reward-one", frame.bindings().get(19).entryId());
        assertEquals("blackmarket.purchase", frame.bindings().get(19).actionFor(ClickKind.LEFT));
        assertFalse(frame.bindings().containsKey(20));
        assertEquals("filler", frame.visuals().get(20).path());
    }

    @Test
    void playerBuyLoreHasNoCommandsCountAndOnlyOneGapBeforeClickHint() throws Exception {
        PublicGuiDefinition player = PublicGuiSchema.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", resource("guis/player-market.yml"),
            PublicMarketMenus.playerContract());
        List<String> rendered = PublicGuiText.lore(
            player.entries().get("offers").sourceItem().lore().lines(),
            Map.of("theme", "#03fc88", "rarity", "Common", "stock", "16",
                "cost", "$250", "chance", "60", "status_line", "→ Click to buy ←"),
            null, null, "entries.offers.source-item.lore.lines");
        assertEquals(List.of("&8ʙᴜᴛᴛᴏɴ", " ", "&eⓘ Information ↓",
            "&7&l | &fRarity: Common", "&7&l | &fStock: #03fc8816",
            "&7&l | &fCost: #03fc88$250", "&7&l | &fChance: #03fc8860%",
            " ", "→ Click to buy ←"), rendered);
        assertFalse(PublicMarketMenus.playerContract().placeholders().contains("commands_line"));
    }

    @Test
    void commandPreviewNeverShowsCommandsCountEvenFromAnOlderInstalledGui() throws Exception {
        YamlConfiguration installed = resource("guis/command-market.yml");
        String lorePath = "entries.offers.source-item.lore.lines";
        List<String> currentLines = installed.getStringList(lorePath);
        assertFalse(currentLines.contains("{commands_line}"));
        List<String> oldLines = new java.util.ArrayList<>(currentLines);
        oldLines.add(oldLines.size() - 2, "{commands_line}");
        installed.set(lorePath, oldLines);
        PublicGuiDefinition command = PublicGuiSchema.parse(PublicMarketMenus.COMMAND,
            "guis/command-market.yml", installed,
            PublicMarketMenus.commandContract());
        Map<String, String> values = Map.of("theme", "#03fc88", "rarity", "Common", "stock", "16",
            "cost", "$250", "chance", "60", "status_line", "Preview only.");
        int slot = command.entries().get("offers").slots().getFirst();
        for (String commandsLine : List.of("", "&7&l | &fCommands: #03fc881")) {
            Map<String, String> offerValues = new java.util.HashMap<>(values);
            offerValues.put("commands_line", commandsLine);
            PublicGuiFrame frame = PublicGuiPlanner.plan(
                new GuiSnapshot<>(1, Map.of(PublicMarketMenus.COMMAND, command)),
                PublicMarketMenus.COMMAND,
                new PublicGuiRenderInput(Map.of(), Map.of(), Map.of(), Map.of("offers", Map.of(slot,
                    new PublicGuiRenderInput.EntryValue("reward-one", new ItemStack(Material.CHEST), offerValues)))));
            PublicGuiFrame adjusted = PublicMarketMenus.withoutRetiredCommandLines(frame);
            PublicGuiFrame.Visual preview = adjusted.visuals().get(slot);
            List<String> rendered = PublicGuiText.lore(preview.sourceConfig().lore().lines(),
                preview.placeholders(), null, null, "entries.offers.source-item.lore.lines");
            assertEquals(List.of("&7&l | &fChance: #03fc8860%", " ", "Preview only."),
                rendered.subList(rendered.size() - 3, rendered.size()));
            assertFalse(rendered.stream().anyMatch(line -> line.contains("Commands:")));
            assertEquals(frame.bindings(), adjusted.bindings());
        }
    }

    @Test
    void pageControlHasNoGhostActionWhenItsEntryIsAbsent() throws Exception {
        PublicGuiDefinition command = PublicGuiSchema.parse(PublicMarketMenus.COMMAND,
            "guis/command-market.yml", resource("guis/command-market.yml"),
            PublicMarketMenus.commandContract());
        PublicGuiFrame empty = PublicGuiPlanner.plan(
            new GuiSnapshot<>(1, Map.of(PublicMarketMenus.COMMAND, command)),
            PublicMarketMenus.COMMAND, new PublicGuiRenderInput(Map.of("time_remaining", "1m",
                "selected_rarity", "Rare", "showing_offers", "0 offers"), Map.of(), Map.of(), Map.of()));
        assertEquals(54, empty.visuals().size());
        assertEquals(List.of(11, 12, 13, 14, 15, 16, 17),
            command.entries().get("rarities").slots());
        assertFalse(command.entries().get("rarities").slots().contains(18));
        assertFalse(command.entries().get("rarities").slots().contains(19));
        for (int slot : List.of(10, 18, 36, 44)) {
            assertTrue(empty.visuals().get(slot).path().endsWith(".empty-item"));
            assertFalse(empty.bindings().containsKey(slot));
        }
        assertEquals("entries.rarities.empty-item", empty.visuals().get(17).path());
        assertEquals("filler", empty.visuals().get(19).path());
        assertFalse(empty.bindings().containsKey(36));
        assertFalse(empty.bindings().containsKey(44));

        PublicGuiFrame next = PublicGuiPlanner.plan(
            new GuiSnapshot<>(1, Map.of(PublicMarketMenus.COMMAND, command)),
            PublicMarketMenus.COMMAND, new PublicGuiRenderInput(Map.of("time_remaining", "1m",
                "selected_rarity", "Rare", "showing_offers", "14 offers"), Map.of(), Map.of(),
                Map.of("next-page", Map.of(44, new PublicGuiRenderInput.EntryValue(
                    "next", null, Map.of("page", "1", "max_page", "2"))))));
        assertEquals("blackmarket.next-page", next.bindings().get(44).actionFor(ClickKind.LEFT));
        assertEquals("entries.next-page", next.visuals().get(44).path());
        assertFalse(next.bindings().containsKey(36));
    }

    @Test
    void emptyRarityRemainsSelectableAndLegacyPlainNameIsResolved() {
        List<RarityDefinition> rarities = List.of(
            new RarityDefinition("common", "Common", null),
            new RarityDefinition("legendary", "&6Legendary", null));
        assertEquals("legendary", PublicMarketMenus.resolveRarity("legendary", rarities, "common"));
        assertEquals("common", PublicMarketMenus.resolveRarity("removed", rarities, "common"));
        assertEquals("Legendary", PublicMarketMenus.rarityPlaceholders("&6Legendary").get("rarity_plain"));
        assertEquals("LEGENDARY", PublicMarketMenus.rarityPlaceholders("&6Legendary").get("rarity_upper"));
        assertTrue(PublicMarketMenus.commandContract().placeholders().contains("rarity_plain"));
    }

    @Test
    void rarityPageControlReachesEntriesBeyondFirstSeven() throws Exception {
        List<RarityDefinition> rarities = java.util.stream.IntStream.range(0, 8)
            .mapToObj(index -> new RarityDefinition("rarity_" + index, "Rarity " + index, null))
            .toList();
        assertEquals(1, PublicMarketMenus.pageForRarity("rarity_7", rarities, 7));
        PublicGuiDefinition command = PublicGuiSchema.parse(PublicMarketMenus.COMMAND,
            "guis/command-market.yml", resource("guis/command-market.yml"),
            PublicMarketMenus.commandContract());
        PublicGuiRenderInput input = new PublicGuiRenderInput(Map.of("time_remaining", "1m"),
            Map.of(), Map.of(), Map.of("next-rarity-page", Map.of(18,
                new PublicGuiRenderInput.EntryValue("next-rarities", null,
                    Map.of("rarity_page", "1", "rarity_max_page", "2")))));
        PublicGuiFrame frame = PublicGuiPlanner.plan(
            new GuiSnapshot<>(1, Map.of(PublicMarketMenus.COMMAND, command)),
            PublicMarketMenus.COMMAND, input);
        assertEquals("blackmarket.next-rarity-page", frame.bindings().get(18).actionFor(ClickKind.LEFT));
        assertFalse(frame.bindings().containsKey(10));
    }

    @Test
    void coldStartRepairsOnlyInvalidFieldWhileReloadRejectsIt() throws Exception {
        YamlConfiguration owner = resource("guis/player-market.yml");
        YamlConfiguration bundled = resource("guis/player-market.yml");
        owner.set("title", "&8Mijn Markt");
        owner.set("rows", 7);
        String installedBytes = owner.saveToString();
        Path installed = temp.resolve("player-market.yml");
        Files.writeString(installed, installedBytes);
        List<PublicGuiStartupFallback.Replacement> replaced = new java.util.ArrayList<>();

        assertThrows(GuiDefinitionException.class, () -> PublicGuiSchema.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", owner, PublicMarketMenus.playerContract()));
        PublicGuiDefinition loaded = PublicGuiStartupFallback.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", owner, bundled, PublicMarketMenus.playerContract(), replaced::add);
        assertEquals(5, loaded.rows());
        assertEquals("&8Mijn Markt", loaded.title());
        assertEquals("rows", replaced.getFirst().path());
        assertEquals(installedBytes, Files.readString(installed));
    }

    private static YamlConfiguration resource(String path) throws Exception {
        try (InputStream stream = PublicMarketGuiTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + path);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
