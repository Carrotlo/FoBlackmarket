package me.foesio.foBlackmarket.gui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.gui.EntryBrowserClick;
import me.foesio.core.gui.EntryBrowserHolder;
import me.foesio.core.gui.EntryBrowserMenus;
import me.foesio.core.gui.EntryBrowserRequest;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RarityListGui extends BaseGui {

    private final int page;
    private final String filter;

    public RarityListGui(FoBlackmarket plugin, Player viewer, int page) {
        this(plugin, viewer, page, "");
    }

    RarityListGui(FoBlackmarket plugin, Player viewer, int page, String filter) {
        super(plugin, viewer, 54, plugin.title("rarities"));
        this.page = Math.max(0, page);
        this.filter = filter == null ? "" : filter.trim();
    }

    static boolean owns(EntryBrowserHolder holder) {
        return holder != null && holder.request().context() instanceof RarityBrowserContext;
    }

    @Override
    public void open() {
        EntryBrowserMenus.open(viewer, request());
    }

    @Override
    protected void render() {
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
    }

    void handleEntryBrowserClick(InventoryClickEvent event, EntryBrowserHolder holder) {
        EntryBrowserClick click = EntryBrowserMenus.handleClick(event.getRawSlot(), holder, event.getClick());
        switch (click.action()) {
            case ENTRY -> {
                if (click.entryId() != null && plugin.getMarketService().getMarketData().getRarities().containsKey(click.entryId())) {
                    plugin.getGuiManager().openRarityEditor(viewer, click.entryId());
                }
            }
            case ADD -> openCreateRarity(holder.request().page(), holder.request().filter());
            case BACK -> plugin.getGuiManager().openMainEditor(viewer);
            case SEARCH -> openSearchInput(holder.request().page(), holder.request().filter());
            case CLEAR_SEARCH -> new RarityListGui(plugin, viewer, 0, "").open();
            case PREVIOUS_PAGE -> new RarityListGui(plugin, viewer, holder.request().page() - 1, holder.request().filter()).open();
            case NEXT_PAGE -> new RarityListGui(plugin, viewer, holder.request().page() + 1, holder.request().filter()).open();
            case EXTRA, NONE -> {
            }
        }
    }

    private EntryBrowserRequest request() {
        List<EntryBrowserRequest.Entry> entries = plugin.getMarketService().getMarketData().getRarities().values().stream()
                .filter(rarity -> matchesFilter(rarity, filter))
                .map(rarity -> EntryBrowserRequest.Entry.of(rarity.getId(), createRarityItem(rarity)))
                .toList();
        return EntryBrowserRequest.builder()
                .title(plugin.title("rarities"))
                .entries(entries)
                .page(page)
                .filter(filter)
                .buttons(buttons)
                .showBack(true)
                .addButton(item(Material.ANVIL, "#03fc88Create Rarity", "#ffffffClick to create a new rarity."))
                .context(new RarityBrowserContext())
                .build();
    }

    private boolean matchesFilter(RarityDefinition rarity, String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || rarity.getId().toLowerCase(Locale.ROOT).contains(normalized)
                || rarity.getDisplayName().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private void openSearchInput(int returnPage, String currentFilter) {
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of("Search rarity ids or display names."),
                "Search",
                currentFilter,
                "Rarity id or name",
                DialogButton.search(),
                DialogButton.cancel(),
                320,
                300,
                64,
                true,
                true,
                false
        );
        EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                viewer,
                request,
                value -> new RarityListGui(plugin, viewer, 0, value).open(),
                () -> new RarityListGui(plugin, viewer, returnPage, currentFilter).open()
        );
    }

    private void openCreateRarity(int returnPage, String returnFilter) {
        EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                viewer,
                editorTextRequest("Enter a new rarity id.", ""),
                input -> {
                    String id = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_").trim();
                    if (id.isBlank()) {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Rarity ids must contain letters or numbers."));
                    } else if (plugin.getMarketService().getMarketData().getRarities().containsKey(id)) {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That rarity id already exists."));
                    } else {
                        plugin.getMarketService().getMarketData().getRarities().put(id, new RarityDefinition(
                                id,
                                "#03fc88New Rarity",
                                new ItemStack(Material.NETHER_STAR)
                        ));
                        plugin.getMarketService().persistMarketDataWithoutRestock();
                        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", id));
                        runForViewer(() -> plugin.getGuiManager().openRarityEditor(viewer, id));
                        return;
                    }
                    runForViewer(() -> new RarityListGui(plugin, viewer, returnPage, returnFilter).open());
                },
                () -> new RarityListGui(plugin, viewer, returnPage, returnFilter).open()
        );
    }

    private ItemStack createRarityItem(RarityDefinition rarity) {
        ItemStack item = rarity.getIcon();
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.NETHER_STAR);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(
                    "§fID: §a" + rarity.getId(),
                    "§fName: " + rarity.getDisplayName(),
                    "§aClick to edit."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private record RarityBrowserContext() {
    }
}
