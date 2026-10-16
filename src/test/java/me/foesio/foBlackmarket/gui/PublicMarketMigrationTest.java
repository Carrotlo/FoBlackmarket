package me.foesio.foBlackmarket.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicMarketMigrationTest {

    @TempDir
    Path temp;

    @Test
    void oldChestSpriteUpdatesWithoutRewritingOwnerTextOrRemoval() throws Exception {
        YamlConfiguration defaults = resource("messages.yml");
        YamlConfiguration messages = resource("released-2.3/messages.yml");
        String path = "messages.opengui-success";
        PublicMarketMigration.upgradeChestSprite(messages);
        assertEquals(defaults.getString(path), messages.getString(path));

        messages.set(path, "{prefix}:chest: {good}Mijn markt is open.");
        PublicMarketMigration.upgradeChestSprite(messages);
        assertEquals("{prefix}:chest_minecart: {good}Mijn markt is open.", messages.getString(path));

        messages.set(path, "{prefix} {good}Opened the market.");
        PublicMarketMigration.upgradeChestSprite(messages);
        assertEquals("{prefix} {good}Opened the market.", messages.getString(path));
    }

    @Test
    void unrelatedExistingDataPreventsFreshInstallClassification() throws Exception {
        assertFalse(PublicMarketMigration.hasPriorData(temp));
        Files.createDirectories(temp.resolve("player-data"));
        Files.writeString(temp.resolve("player-data/existing.db"), "existing");
        assertTrue(PublicMarketMigration.hasPriorData(temp));
        assertFalse(new PublicMarketMigration.SourceSnapshot(0, Map.of(), true).freshInstall());
    }

    @Test
    void trackedLegacyInstallMigratesCustomTextIconsAndOnlyInvalidFields() throws Exception {
        YamlConfiguration config = resource("tracked-1.5/config.yml");
        YamlConfiguration market = resource("tracked-1.5/market.yml");
        config.set("colors.theme", "#118855");
        config.set("titles.market", "&8Mijn Zwarte Markt");
        config.set("messages.market-bought", "Gekocht: {reward}");
        config.set("messages.market-sold-out", null);
        market.set("gui.info-slot", 5);
        market.set("rarities.common.icon.name", "Mijn gewone buit");
        market.set("rewards.starter_food.icon.name", "Mijn eten");
        market.set("rewards.starter_food.stock", "many");
        byte[] oldConfig = config.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] oldMarket = market.saveToString().getBytes(StandardCharsets.UTF_8);
        Files.write(temp.resolve("config.yml"), oldConfig);
        Files.write(temp.resolve("market.yml"), oldMarket);
        var source = new PublicMarketMigration.SourceSnapshot(0,
            Map.of("config.yml", oldConfig, "market.yml", oldMarket));

        var transaction = new PublicMarketMigration(null).prepare(source, temp);
        transaction.commit();
        YamlConfiguration player = new YamlConfiguration();
        player.load(temp.resolve("guis/player-market.yml").toFile());
        YamlConfiguration command = new YamlConfiguration();
        command.load(temp.resolve("guis/command-market.yml").toFile());
        YamlConfiguration migratedMessages = new YamlConfiguration();
        migratedMessages.load(temp.resolve("messages.yml").toFile());
        YamlConfiguration migratedMarket = new YamlConfiguration();
        migratedMarket.load(temp.resolve("market.yml").toFile());

        assertEquals("&8Mijn Zwarte Markt", player.getString("title"));
        assertEquals("&8Mijn Zwarte Markt", command.getString("title"));
        assertEquals(List.of(5), player.getIntegerList("decorations.info.slots"));
        assertEquals("#118855", migratedMessages.getString("tokens.theme"));
        assertEquals("Gekocht: {reward}", migratedMessages.getString("messages.market-bought"));
        assertFalse(migratedMessages.isSet("messages.market-sold-out"));
        assertEquals("Mijn gewone buit", migratedMarket.getString("rarities.common.icon.name"));
        assertEquals("Mijn eten", migratedMarket.getString("rewards.starter_food.icon.name"));
        assertEquals(16, migratedMarket.getInt("rewards.starter_food.stock"));
        assertFalse(migratedMarket.isSet("gui.close-slot"));
        assertEquals(new String(oldConfig, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("config.yml.gui-v0-backup")));
        assertEquals(new String(oldMarket, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("market.yml.gui-v0-backup")));
        transaction.rollback();
        assertEquals(new String(oldMarket, StandardCharsets.UTF_8), Files.readString(temp.resolve("market.yml")));
    }

    @Test
    void releasedV3GuiDefaultsUpgradeWithoutStrippingNewControlsOrKeepingOldStyle() throws Exception {
        byte[] oldPlayer = resourceBytes("legacy/public-gui/player-market-v3.yml");
        byte[] oldCommand = resourceBytes("legacy/public-gui/command-market-v3.yml");
        byte[] oldMarket = resourceBytes("market.yml");
        byte[] oldMessages = resourceBytes("released-2.3/messages.yml");
        byte[] oldConfig = "config-version: 3\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve("guis"));
        Files.write(temp.resolve("guis/player-market.yml"), oldPlayer);
        Files.write(temp.resolve("guis/command-market.yml"), oldCommand);
        Files.write(temp.resolve("market.yml"), oldMarket);
        Files.write(temp.resolve("messages.yml"), oldMessages);
        Files.write(temp.resolve("config.yml"), oldConfig);
        var source = new PublicMarketMigration.SourceSnapshot(3, Map.of(
            "guis/player-market.yml", oldPlayer, "guis/command-market.yml", oldCommand,
            "market.yml", oldMarket, "messages.yml", oldMessages, "config.yml", oldConfig));

        var transaction = new PublicMarketMigration(null).prepare(source, temp);
        transaction.commit();
        YamlConfiguration player = load(temp.resolve("guis/player-market.yml"));
        YamlConfiguration command = load(temp.resolve("guis/command-market.yml"));
        YamlConfiguration messages = load(temp.resolve("messages.yml"));
        YamlConfiguration newPlayer = resource("guis/player-market.yml");
        YamlConfiguration newCommand = resource("guis/command-market.yml");
        YamlConfiguration newMessages = resource("messages.yml");

        assertEquals(newPlayer.getStringList("entries.offers.source-item.lore.lines"),
            player.getStringList("entries.offers.source-item.lore.lines"));
        assertEquals(newCommand.getStringList("entries.rarities.source-item.lore.lines"),
            command.getStringList("entries.rarities.source-item.lore.lines"));
        assertEquals(newCommand.getString("entries.previous-page.item.name"),
            command.getString("entries.previous-page.item.name"));
        assertEquals(newCommand.getStringList("entries.previous-page.item.lore"),
            command.getStringList("entries.previous-page.item.lore"));
        assertEquals(newCommand.getString("entries.next-page.item.name"),
            command.getString("entries.next-page.item.name"));
        assertEquals(newCommand.getStringList("entries.next-page.item.lore"),
            command.getStringList("entries.next-page.item.lore"));
        assertEquals(newMessages.getString("gui.rarity-unselected-click-line"),
            messages.getString("gui.rarity-unselected-click-line"));
        assertEquals(newMessages.getString("gui.command-preview-line"),
            messages.getString("gui.command-preview-line"));
        assertFalse(messages.isSet("gui.command-commands-line"));
        assertFalse(command.getStringList("entries.offers.source-item.lore.lines")
            .contains("{commands_line}"));
        assertEquals(newMessages.getString("messages.opengui-success"),
            messages.getString("messages.opengui-success"));
        assertFalse(messages.isSet("gui.player-commands-line"));
        assertEquals(newMessages.getString("gui.player-buy-line"),
            messages.getString("gui.player-buy-line"));
        assertEquals(List.of("#ffffffRarity: {rarity}", "#ffffffStock: #03fc88{stock}",
                "#ffffffCost: #03fc88{cost}", "#ffffffChance: #03fc88{chance}%",
                "{commands_line}", "{status_line}"),
            resource("legacy/public-gui/player-market-v3.yml").getStringList("offer-item.lore"));
        assertEquals(new String(oldCommand, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/command-market.yml.gui-v3-backup")));
        transaction.rollback();
        assertEquals(new String(oldPlayer, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/player-market.yml")));
    }

    @Test
    void releasedV3OwnerOverridesAndOptionalRemovalSurviveUpgrade() throws Exception {
        YamlConfiguration oldPlayer = resource("legacy/public-gui/player-market-v3.yml");
        YamlConfiguration oldCommand = resource("legacy/public-gui/command-market-v3.yml");
        oldPlayer.set("title", "Grün Markt");
        oldPlayer.set("items.info.lore", List.of());
        oldCommand.set("items.timer.name", "Eigener Timer");
        oldCommand.set("items.timer.lore", List.of("Meine Zeit: {time_remaining}"));
        oldCommand.set("offer-preview-lore", "Eigene Vorschau");
        oldCommand.set("rarity-item.unselected", null);
        byte[] playerBytes = oldPlayer.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] commandBytes = oldCommand.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] marketBytes = resourceBytes("market.yml");
        byte[] messagesBytes = resourceBytes("released-2.3/messages.yml");
        byte[] configBytes = "config-version: 3\n".getBytes(StandardCharsets.UTF_8);
        var source = new PublicMarketMigration.SourceSnapshot(3, Map.of(
            "guis/player-market.yml", playerBytes, "guis/command-market.yml", commandBytes,
            "market.yml", marketBytes, "messages.yml", messagesBytes, "config.yml", configBytes));

        var transaction = new PublicMarketMigration(null).prepare(source, temp);
        transaction.commit();
        YamlConfiguration player = load(temp.resolve("guis/player-market.yml"));
        YamlConfiguration command = load(temp.resolve("guis/command-market.yml"));
        YamlConfiguration messages = load(temp.resolve("messages.yml"));
        assertEquals("Grün Markt", player.getString("title"));
        assertEquals(List.of(), player.getStringList("decorations.info.lore"));
        assertEquals("Eigener Timer", command.getString("decorations.timer.name"));
        assertEquals(List.of("Meine Zeit: {time_remaining}"),
            command.getStringList("decorations.timer.lore"));
        assertEquals("Eigene Vorschau", messages.getString("gui.command-preview-line"));
        assertFalse(messages.isSet("gui.rarity-unselected"));
        assertEquals(new String(commandBytes, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/command-market.yml.gui-v3-backup")));
    }

    @Test
    void v5UpgradeRemovesOldDefaultCommandsLineWithoutLosingOwnerEdits() throws Exception {
        YamlConfiguration oldPlayer = resource("legacy/public-gui/player-market-v5.yml");
        YamlConfiguration oldMessages = resource("schema-v5/messages.yml");
        oldPlayer.set("title", "&8Mein Markt");
        oldPlayer.set("decorations.info.lore", List.of());
        oldMessages.set("messages.market-bought", "Gekauft: {reward}");
        byte[] playerBytes = oldPlayer.saveToString().getBytes(StandardCharsets.UTF_8);
        YamlConfiguration oldCommand = resource("guis/command-market.yml");
        for (String entry : List.of("rarities", "previous-rarity-page", "next-rarity-page",
            "previous-page", "next-page")) {
            oldCommand.set("entries." + entry + ".empty-item", null); // Released v5 had no such items.
        }
        oldCommand.set("entries.previous-page.empty-item.material", "BLUE_STAINED_GLASS_PANE");
        oldCommand.set("entries.previous-page.empty-item.name", "Eigener Hintergrund");
        oldCommand.set("title", "&8Eigener Browser");
        String offerLorePath = "entries.offers.source-item.lore.lines";
        List<String> oldOfferLore = new java.util.ArrayList<>(oldCommand.getStringList(offerLorePath));
        oldOfferLore.add(oldOfferLore.size() - 2, "{commands_line}");
        oldCommand.set(offerLorePath, oldOfferLore);
        byte[] commandBytes = oldCommand.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] messagesBytes = oldMessages.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] marketBytes = resourceBytes("market.yml");
        byte[] configBytes = "config-version: 5\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve("guis"));
        Files.write(temp.resolve("guis/player-market.yml"), playerBytes);
        Files.write(temp.resolve("guis/command-market.yml"), commandBytes);
        Files.write(temp.resolve("messages.yml"), messagesBytes);
        Files.write(temp.resolve("market.yml"), marketBytes);
        Files.write(temp.resolve("config.yml"), configBytes);
        var source = new PublicMarketMigration.SourceSnapshot(5, Map.of(
            "guis/player-market.yml", playerBytes, "guis/command-market.yml", commandBytes,
            "messages.yml", messagesBytes, "market.yml", marketBytes, "config.yml", configBytes));

        var transaction = new PublicMarketMigration(null).prepare(source, temp);
        transaction.commit();
        YamlConfiguration player = load(temp.resolve("guis/player-market.yml"));
        YamlConfiguration command = load(temp.resolve("guis/command-market.yml"));
        YamlConfiguration messages = load(temp.resolve("messages.yml"));
        assertEquals("&8Mein Markt", player.getString("title"));
        assertEquals("&8Eigener Browser", command.getString("title"));
        assertEquals("Eigener Hintergrund", command.getString(
            "entries.previous-page.empty-item.name"));
        assertFalse(command.getStringList(offerLorePath).contains("{commands_line}"));
        assertFalse(messages.isSet("gui.command-commands-line"));
        for (String entry : List.of("rarities", "previous-rarity-page", "next-rarity-page",
            "next-page")) {
            String path = "entries." + entry + ".empty-item.material";
            assertEquals("GRAY_STAINED_GLASS_PANE", command.getString(path));
        }
        assertEquals(List.of(), player.getStringList("decorations.info.lore"));
        assertEquals(resource("guis/player-market.yml").getStringList(
            "entries.offers.source-item.lore.lines"), player.getStringList(
            "entries.offers.source-item.lore.lines"));
        assertFalse(player.getStringList("entries.offers.source-item.lore.lines")
            .contains("{commands_line}"));
        assertFalse(messages.isSet("gui.player-commands-line"));
        assertEquals("Gekauft: {reward}", messages.getString("messages.market-bought"));
        assertEquals(new String(playerBytes, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/player-market.yml.gui-v5-backup")));
        assertEquals(new String(commandBytes, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/command-market.yml.gui-v5-backup")));
        transaction.rollback();
        assertEquals(new String(playerBytes, StandardCharsets.UTF_8),
            Files.readString(temp.resolve("guis/player-market.yml")));
    }

    @Test
    void v5UpgradeRemovesRetiredTokenFromCustomLoreAndRepairsInvalidSibling() throws Exception {
        YamlConfiguration oldPlayer = resource("legacy/public-gui/player-market-v5.yml");
        oldPlayer.set("entries.offers.source-item.lore.lines", List.of(
            "Eigener Text", "{commands_line}", " ", "{status_line}"));
        oldPlayer.set("filler.material", "NOT_A_MATERIAL");
        oldPlayer.set("decorations.info.name", "Meine Info");
        byte[] playerBytes = oldPlayer.saveToString().getBytes(StandardCharsets.UTF_8);
        byte[] commandBytes = resourceBytes("guis/command-market.yml");
        byte[] messagesBytes = resourceBytes("schema-v5/messages.yml");
        byte[] marketBytes = resourceBytes("market.yml");
        byte[] configBytes = "config-version: 5\n".getBytes(StandardCharsets.UTF_8);
        var source = new PublicMarketMigration.SourceSnapshot(5, Map.of(
            "guis/player-market.yml", playerBytes, "guis/command-market.yml", commandBytes,
            "messages.yml", messagesBytes, "market.yml", marketBytes, "config.yml", configBytes));

        var transaction = new PublicMarketMigration(null).prepare(source, temp);
        transaction.commit();
        YamlConfiguration player = load(temp.resolve("guis/player-market.yml"));
        assertEquals(List.of("Eigener Text", " ", "{status_line}"),
            player.getStringList("entries.offers.source-item.lore.lines"));
        assertEquals("GRAY_STAINED_GLASS_PANE", player.getString("filler.material"));
        assertEquals("Meine Info", player.getString("decorations.info.name"));
    }

    @Test
    void unparseableLegacyMarketRemainsUnchangedAndUnbackedUp() throws Exception {
        byte[] oldConfig = resource("tracked-1.5/config.yml").saveToString()
            .getBytes(StandardCharsets.UTF_8);
        byte[] invalidMarket = "gui: [broken\n".getBytes(StandardCharsets.UTF_8);
        Files.write(temp.resolve("config.yml"), oldConfig);
        Files.write(temp.resolve("market.yml"), invalidMarket);
        var source = new PublicMarketMigration.SourceSnapshot(0,
            Map.of("config.yml", oldConfig, "market.yml", invalidMarket));
        assertThrows(java.io.IOException.class, () -> new PublicMarketMigration(null).prepare(source, temp));
        assertEquals("gui: [broken\n", Files.readString(temp.resolve("market.yml")));
        assertFalse(Files.exists(temp.resolve("market.yml.gui-v0-backup")));
        assertFalse(Files.exists(temp.resolve("guis/player-market.yml")));
    }

    @Test
    void multiFileTransactionKeepsOriginalByteBackupsAndRollsBackActiveFiles() throws Exception {
        byte[] oldPlayer = "title: 'Grün'\n".getBytes(StandardCharsets.UTF_8);
        byte[] oldMarket = "gui: {size: 45}\n".getBytes(StandardCharsets.UTF_8);
        byte[] oldConfig = "config-version: 4\n".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve("guis"));
        Files.write(temp.resolve("guis/player-market.yml"), oldPlayer);
        Files.write(temp.resolve("market.yml"), oldMarket);
        Files.write(temp.resolve("config.yml"), oldConfig);
        var snapshot = new PublicMarketMigration.SourceSnapshot(4, Map.of(
            "guis/player-market.yml", oldPlayer, "market.yml", oldMarket, "config.yml", oldConfig));
        var transaction = new PublicMarketMigration.Transaction(temp, snapshot, Map.of(
            "guis/player-market.yml", "title: New\n".getBytes(StandardCharsets.UTF_8),
            "market.yml", "gui: {}\n".getBytes(StandardCharsets.UTF_8)));
        transaction.commit();
        assertEquals("title: New\n", Files.readString(temp.resolve("guis/player-market.yml")));
        assertEquals("title: 'Grün'\n", Files.readString(
            temp.resolve("guis/player-market.yml.gui-v4-backup")));
        assertEquals("gui: {size: 45}\n", Files.readString(temp.resolve("market.yml.gui-v4-backup")));
        assertEquals("config-version: 4\n", Files.readString(temp.resolve("config.yml.gui-v4-backup")));
        transaction.rollback();
        assertEquals("title: 'Grün'\n", Files.readString(temp.resolve("guis/player-market.yml")));
        assertEquals("gui: {size: 45}\n", Files.readString(temp.resolve("market.yml")));
        assertTrue(Files.exists(temp.resolve("guis/player-market.yml.gui-v4-backup")));
    }

    @Test
    void freshInstallRollbackRemovesNewFilesWithoutCreatingUpgradeBackups() throws Exception {
        var source = new PublicMarketMigration.SourceSnapshot(0, Map.of());
        var transaction = new PublicMarketMigration.Transaction(temp, source, Map.of(
            "guis/player-market.yml", "menu-id: blackmarket.player\n".getBytes(StandardCharsets.UTF_8),
            "messages.yml", "tokens: {}\n".getBytes(StandardCharsets.UTF_8)));
        transaction.commit();
        assertTrue(Files.isRegularFile(temp.resolve("guis/player-market.yml")));
        assertFalse(Files.exists(temp.resolve("guis/player-market.yml.gui-v0-backup")));
        transaction.rollback();
        assertFalse(Files.exists(temp.resolve("guis/player-market.yml")));
        assertFalse(Files.exists(temp.resolve("messages.yml")));
    }

    @Test
    void mapsCustomPlayerHeadFillerAndLayoutToCoreSchema() throws Exception {
        YamlConfiguration legacy = resource("legacy/public-gui/market-layout-v1.yml");
        YamlConfiguration defaults = resource("legacy/public-gui/market-layout-v1.yml");
        YamlConfiguration target = resource("guis/player-market.yml");
        String skin = "https://textures.minecraft.net/texture/abc123";
        legacy.set("gui.size", 54);
        legacy.set("gui.info-slot", 5);
        legacy.set("gui.filler.material", "PLAYER_HEAD");
        legacy.set("gui.filler.name", "Grün Hintergrund");
        legacy.set("gui.filler.lore", List.of("Über uns"));
        legacy.set("gui.filler.skull.textures.skin", skin);
        legacy.set("gui.slot-rarities.24", null);
        legacy.set("gui.slot-rarities.25", "legendary");

        new PublicMarketMigration(null).overlayLegacyMarket(legacy, defaults, target);
        PublicGuiDefinition parsed = PublicGuiSchema.parse(PublicMarketMenus.PLAYER,
            "guis/player-market.yml", target, PublicMarketMenus.playerContract());
        assertEquals(6, parsed.rows());
        assertEquals(List.of(5), parsed.decorations().get("info").slots());
        assertEquals(List.of(20, 21, 22, 23, 25), parsed.entries().get("offers").slots());
        assertEquals("Grün Hintergrund", parsed.filler().name());
        assertEquals(List.of("Über uns"), parsed.filler().lore());
        assertEquals(skin, parsed.filler().skullSkinUrl());
    }

    @Test
    void preservesMissingLegacyFillerAndRejectsUnrepresentableMetadata() throws Exception {
        YamlConfiguration defaults = resource("legacy/public-gui/market-layout-v1.yml");
        YamlConfiguration legacy = resource("legacy/public-gui/market-layout-v1.yml");
        legacy.set("gui.filler", null);
        YamlConfiguration target = resource("guis/player-market.yml");
        new PublicMarketMigration(null).overlayLegacyMarket(legacy, defaults, target);
        assertFalse(target.getBoolean("filler.enabled"));

        legacy = resource("legacy/public-gui/market-layout-v1.yml");
        legacy.set("gui.filler.enchantments.sharpness", 1);
        YamlConfiguration active = resource("guis/player-market.yml");
        YamlConfiguration finalLegacy = legacy;
        String original = finalLegacy.saveToString();
        assertThrows(java.io.IOException.class,
            () -> new PublicMarketMigration(null).overlayLegacyMarket(finalLegacy, defaults, active));
        assertEquals(original, finalLegacy.saveToString());
    }

    @Test
    void emptyLegacyAssignmentsKeepMenuAvailableWithoutRestoringOffers() throws Exception {
        YamlConfiguration defaults = resource("legacy/public-gui/market-layout-v1.yml");
        YamlConfiguration legacy = resource("legacy/public-gui/market-layout-v1.yml");
        legacy.set("gui.slot-rarities", null);
        YamlConfiguration target = resource("guis/player-market.yml");
        new PublicMarketMigration(null).overlayLegacyMarket(legacy, defaults, target);
        assertEquals(List.of(20, 21, 22, 23, 24), target.getIntegerList("entries.offers.slots"));
        assertFalse(legacy.isSet("gui.slot-rarities"));
    }

    @Test
    void newRarityPagerYieldsToOlderCustomRaritySlots() throws Exception {
        YamlConfiguration command = resource("guis/command-market.yml");
        command.set("entries.rarities.slots", List.of(10, 11, 12, 13, 14, 15, 16));
        PublicMarketMigration.relocateNewRarityControls(command);
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16),
            command.getIntegerList("entries.rarities.slots"));
        assertEquals(List.of(9), command.getIntegerList("entries.previous-rarity-page.slots"));
        PublicGuiSchema.parse(PublicMarketMenus.COMMAND, "guis/command-market.yml", command,
            PublicMarketMenus.commandContract());
    }

    @Test
    void legacyConfigMessagesKeepCustomTextAndIntentionalRemovals() throws Exception {
        YamlConfiguration oldDefaults = resource("legacy/public-gui/config-messages-v1.yml");
        YamlConfiguration old = resource("legacy/public-gui/config-messages-v1.yml");
        YamlConfiguration target = resource("messages.yml");
        old.set("colors.theme", "#228844");
        old.set("prefix", "Mijn Markt »");
        old.set("messages.market-bought", "Gekocht: {reward}");
        old.set("messages.market-sold-out", null);
        old.set("titles.rewards", "Beloningen");

        new PublicMarketMigration(null).overlayLegacyConfigMessages(old, oldDefaults, target);

        assertEquals("#228844", target.getString("tokens.theme"));
        assertEquals("Mijn Markt »", target.getString("tokens.prefix"));
        assertEquals("Gekocht: {reward}", target.getString("messages.market-bought"));
        assertFalse(target.isSet("messages.market-sold-out"));
        assertEquals("Beloningen", target.getString("titles.rewards"));
        assertEquals("{prefix} {bad}You do not have permission.",
            target.getString("messages.no-permission"));
    }

    @Test
    void invalidListMemberUsesOnlyItsBundledCounterpart() {
        assertEquals(20, PublicMarketMigration.replacementListElement(
            List.of(19, "invalid", 21), List.of(20, 21, 22), 1, true));
        assertEquals("new", PublicMarketMigration.replacementListElement(
            List.of("owner text", 42), List.of("default text", "new"), 1, false));
        assertEquals(null, PublicMarketMigration.replacementListElement(
            List.of("owner text", 42), List.of("default text"), 1, false));
    }

    @Test
    void customLegacyTimeUnitsBecomeWholeOwnerEditablePhrases() throws Exception {
        YamlConfiguration oldDefaults = resource("legacy/public-gui/config-messages-v1.yml");
        YamlConfiguration old = resource("legacy/public-gui/config-messages-v1.yml");
        YamlConfiguration newest = resource("messages.yml");
        YamlConfiguration target = resource("messages.yml");
        old.set("settings.time-format.hour", " uur");
        old.set("settings.time-format.second", " sec");
        target.set("gui.duration-day-other", "{value} dagen");
        new PublicMarketMigration(null).overlayLegacyDuration(old, oldDefaults, target, newest);
        assertEquals("{value} uur", target.getString("gui.duration-hour-one"));
        assertEquals("{value} uur", target.getString("gui.duration-hour-other"));
        assertEquals("{value} sec", target.getString("gui.duration-zero"));
        assertEquals("{value} dagen", target.getString("gui.duration-day-other"));
    }

    @Test
    void invalidMarketStockUsesBundledValueButKeepsCustomIconName() throws Exception {
        YamlConfiguration defaults = resource("market.yml");
        YamlConfiguration old = resource("market.yml");
        old.set("rewards.starter_food.stock", "many");
        old.set("rewards.starter_food.icon.name", "Mijn eten");
        var migration = new PublicMarketMigration(null);
        migration.repairMarketValue(old, defaults, "rewards.starter_food.stock",
            value -> value instanceof Integer count && count >= 0);
        assertEquals(16, old.getInt("rewards.starter_food.stock"));
        assertEquals("Mijn eten", old.getString("rewards.starter_food.icon.name"));

        old.set("rewards.custom.stock", "many");
        assertThrows(java.io.IOException.class, () -> migration.repairMarketValue(old, defaults,
            "rewards.custom.stock", value -> value instanceof Integer count && count >= 0));
    }

    @Test
    void invalidOptionalSkullUrlIsRemovedWithoutLosingCustomFillerOrInfo() throws Exception {
        YamlConfiguration defaults = resource("guis/player-market.yml");
        YamlConfiguration candidate = resource("guis/player-market.yml");
        candidate.set("filler.material", "PLAYER_HEAD");
        candidate.set("filler.skull-skin-url", "not-a-url");
        candidate.set("decorations.info.name", "Mijn markt");
        new PublicMarketMigration(null).repairGui("guis/player-market.yml", PublicMarketMenus.PLAYER,
            candidate, defaults, PublicMarketMenus.playerContract());
        assertFalse(candidate.isSet("filler.skull-skin-url"));
        assertEquals("PLAYER_HEAD", candidate.getString("filler.material"));
        assertEquals("Mijn markt", candidate.getString("decorations.info.name"));
    }

    @Test
    void migratedDisabledSoundKeepsBlankKeyAndOwnerPitch() throws Exception {
        YamlConfiguration sounds = resource("sounds.yml");
        YamlConfiguration defaults = resource("sounds.yml");
        assertTrue(sounds.isConfigurationSection("market.purchase-failure"));
        sounds.set("market.purchase-failure.enabled", false);
        sounds.set("market.purchase-failure.sound", "");
        sounds.set("market.purchase-failure.pitch", 1.7);
        new PublicMarketMigration(null).repairSounds(sounds, defaults);
        assertFalse(sounds.getBoolean("market.purchase-failure.enabled"));
        assertEquals("", sounds.getString("market.purchase-failure.sound"));
        assertEquals(1.7, sounds.getDouble("market.purchase-failure.pitch"));
    }

    @Test
    void malformedOldSoundEventRepairsOnlyThatEvent() throws Exception {
        YamlConfiguration sounds = resource("sounds.yml");
        YamlConfiguration defaults = resource("sounds.yml");
        sounds.set("market.purchase-failure", 42);
        sounds.set("market.purchase-success.pitch", 1.8);
        new PublicMarketMigration(null).repairSounds(sounds, defaults);
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(sounds.saveToString());
        assertEquals(defaults.getString("market.purchase-failure.sound"),
            parsed.getString("market.purchase-failure.sound"));
        assertEquals(1.8, parsed.getDouble("market.purchase-success.pitch"));
    }

    @Test
    void malformedOldMessageRootAndLocaleUseBundledValuesOnlyThere() throws Exception {
        YamlConfiguration messages = resource("messages.yml");
        YamlConfiguration defaults = resource("messages.yml");
        messages.set("tokens.theme", "#118855");
        messages.set("messages", 42);
        messages.set("gui.number-locale", "en_US_bad_!");
        new PublicMarketMigration(null).repairMessages(messages, defaults);
        assertEquals("#118855", messages.getString("tokens.theme"));
        assertEquals(defaults.getString("messages.market-bought"),
            messages.getString("messages.market-bought"));
        assertEquals("en-US", messages.getString("gui.number-locale"));
    }

    private static YamlConfiguration resource(String path) throws Exception {
        try (InputStream stream = PublicMarketMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing " + path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream stream = PublicMarketMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing " + path);
            return stream.readAllBytes();
        }
    }

    private static YamlConfiguration load(Path path) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        return yaml;
    }
}
