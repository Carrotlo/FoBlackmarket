package me.foesio.foBlackmarket.gui;

import java.util.Map;
import java.util.List;
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
        inventory.setItem(10, button(Material.CLOCK, "#03fc88", "Restock Interval", List.of(
            "Current: " + formatRestockInterval(),
            "Examples: 45m, 1h30m, 2d."
        ), "edit the restock interval"));
        boolean announceRestocks = plugin.getConfigManager().isAnnounceRestocks();
        inventory.setItem(11, me.foesio.core.editor.EditorItemFactory.toggle(viewer, "Restock Broadcasts", announceRestocks));
        inventory.setItem(12, button(Material.CHEST, "#03fc88", "Reward Sets", List.of(
            "Create and edit reward sets.",
            "Stock, cost, commands, chance, item."
        ), "manage reward sets"));
        inventory.setItem(13, button(Material.NETHER_STAR, "#03fc88", "Rarities",
            List.of("Edit rarity ids, names and icons."), "manage rarities"));
        inventory.setItem(14, button(Material.ITEM_FRAME, "#03fc88", "Market Layout",
            List.of("Edit the live player market GUI."), "edit the market layout"));
        inventory.setItem(15, button(Material.HOPPER, "#03fc88", "Restock Now",
            List.of("Immediately reroll active stock."), "restock now"));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        switch (slot) {
            case 10 -> {
                openEditorInput(
                    editorTextRequest("Enter the restock interval. Use minutes or a duration like 45m, 1h30m, or 2d.", formatRestockInterval()),
                    input -> {
                        Long minutes = parseRestockIntervalMinutes(input);
                        if (minutes != null) {
                            plugin.getConfigManager().setRestockIntervalMinutes(minutes);
                            plugin.getMarketService().rescheduleRestockFromNow();
                            editorSounds.save(viewer);
                            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "restock interval"));
                        } else {
                            editorSounds.error(viewer);
                            plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Use a whole-minute duration like 30m, 1h, 1h30m, or 2d."));
                        }
                        runForViewer(() -> plugin.getGuiManager().openMainEditorSilently(viewer));
                    },
                    () -> {
                        plugin.getMessages().send(viewer, "messages.editor-cancelled");
                        plugin.getGuiManager().openMainEditorSilently(viewer);
                    }
                );
            }
            case 11 -> {
                boolean enabled = !plugin.getConfigManager().isAnnounceRestocks();
                plugin.getConfigManager().setAnnounceRestocks(enabled);
                editorSounds.toggle(viewer, enabled);
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
