package me.foesio.foBlackmarket.gui;

import java.util.Map;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.number.DurationParser;
import me.foesio.core.number.TickDuration;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class MainEditorGui extends BaseGui {

    public MainEditorGui(FoBlackmarket plugin, Player viewer) {
        super(plugin, viewer, 27, plugin.title("admin-main"));
    }

    @Override
    protected void render() {
        inventory.setItem(10, item(
            Material.CLOCK,
            "#03fc88Restock Interval",
            "#ffffffCurrent: #03fc88" + formatRestockInterval(),
            "#ffffffClick to edit.",
            "#a7b8b0Examples: 45m, 1h30m, 2d."
        ));
        inventory.setItem(11, item(
            plugin.getConfigManager().isAnnounceRestocks() ? Material.LIME_DYE : Material.RED_DYE,
            plugin.getConfigManager().isAnnounceRestocks() ? "#3ecf8eRestock Broadcasts Enabled" : "#ff5d73Restock Broadcasts Disabled",
            "#ffffffClick to toggle the broadcast."
        ));
        inventory.setItem(12, item(
            Material.CHEST,
            "#03fc88Reward Sets",
            "#ffffffCreate and edit reward sets.",
            "#ffffffStock, cost, commands, chance, item."
        ));
        inventory.setItem(13, item(
            Material.NETHER_STAR,
            "#03fc88Rarities",
            "#ffffffEdit rarity ids, names and icons."
        ));
        inventory.setItem(14, item(
            Material.ITEM_FRAME,
            "#03fc88Market Layout",
            "#ffffffEdit the live player market GUI."
        ));
        inventory.setItem(15, item(
            Material.HOPPER,
            "#03fc88Restock Now",
            "#ffffffImmediately reroll active stock."
        ));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        switch (slot) {
            case 10 -> {
                EditorDialogInputs.openTextFromInventory(
                    plugin,
                    plugin.getCore().inventoryCloseSuppressor(),
                    plugin.getCore().dialogService(),
                    viewer,
                    editorTextRequest("Enter the restock interval. Use minutes or a duration like 45m, 1h30m, or 2d.", formatRestockInterval()),
                    input -> {
                        Long minutes = parseRestockIntervalMinutes(input);
                        if (minutes != null) {
                            plugin.getConfigManager().setRestockIntervalMinutes(minutes);
                            plugin.getMarketService().rescheduleRestockFromNow();
                            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "restock interval"));
                        } else {
                            plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Use a whole-minute duration like 30m, 1h, 1h30m, or 2d."));
                        }
                        runForViewer(() -> plugin.getGuiManager().openMainEditor(viewer));
                    },
                    () -> {
                        plugin.getMessages().send(viewer, "messages.editor-cancelled");
                        plugin.getGuiManager().openMainEditor(viewer);
                    }
                );
            }
            case 11 -> {
                plugin.getConfigManager().setAnnounceRestocks(!plugin.getConfigManager().isAnnounceRestocks());
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "restock broadcast"));
                refresh();
            }
            case 12 -> plugin.getGuiManager().openRewardList(viewer, 0);
            case 13 -> plugin.getGuiManager().openRarityList(viewer, 0);
            case 14 -> plugin.getGuiManager().openLayoutEditor(viewer);
            case 15 -> {
                plugin.getMarketService().restockNow(true);
                plugin.getMessages().send(viewer, "messages.restock-success");
                refresh();
            }
            default -> {
            }
        }
    }

    private String formatRestockInterval() {
        long minutes = plugin.getConfigManager().getRestockIntervalMinutes();
        try {
            return TickDuration.ofSeconds(Math.multiplyExact(minutes, TickDuration.SECONDS_PER_MINUTE)).compact();
        } catch (ArithmeticException exception) {
            return minutes + "m";
        }
    }

    private Long parseRestockIntervalMinutes(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.matches("\\d+")) {
            try {
                long minutes = Long.parseLong(trimmed);
                return minutes >= 1L ? minutes : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        String compact = trimmed.replaceAll("\\s+", "");
        var parsed = DurationParser.parse(compact);
        if (parsed.isEmpty()) {
            return null;
        }
        long seconds = parsed.get().secondsFloor();
        if (seconds < TickDuration.SECONDS_PER_MINUTE || seconds % TickDuration.SECONDS_PER_MINUTE != 0L) {
            return null;
        }
        return seconds / TickDuration.SECONDS_PER_MINUTE;
    }
}
