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
import me.foesio.foBlackmarket.market.CostDefinition;
import me.foesio.foBlackmarket.market.CostType;
import me.foesio.foBlackmarket.market.MarketRepository;
import me.foesio.foBlackmarket.market.RewardDefinition;
import me.foesio.foBlackmarket.market.StockMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RewardListGui extends BaseGui {

    private final int page;
    private final String filter;

    public RewardListGui(FoBlackmarket plugin, Player viewer, int page) {
        this(plugin, viewer, page, "");
    }

    RewardListGui(FoBlackmarket plugin, Player viewer, int page, String filter) {
        super(plugin, viewer, 54, plugin.title("rewards"));
        this.page = Math.max(0, page);
        this.filter = filter == null ? "" : filter.trim();
    }

    static boolean owns(EntryBrowserHolder holder) {
        return holder != null && holder.request().context() instanceof RewardBrowserContext;
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
                if (click.entryId() != null && plugin.getMarketService().getMarketData().getRewards().containsKey(click.entryId())) {
                    plugin.getGuiManager().openRewardEditor(viewer, click.entryId());
                }
            }
            case ADD -> openCreateReward(holder.request().page(), holder.request().filter());
            case BACK -> plugin.getGuiManager().openMainEditor(viewer);
            case SEARCH -> openSearchInput(holder.request().page(), holder.request().filter());
            case CLEAR_SEARCH -> new RewardListGui(plugin, viewer, 0, "").open();
            case PREVIOUS_PAGE -> new RewardListGui(plugin, viewer, holder.request().page() - 1, holder.request().filter()).open();
            case NEXT_PAGE -> new RewardListGui(plugin, viewer, holder.request().page() + 1, holder.request().filter()).open();
            case EXTRA, NONE -> {
            }
        }
    }

    private EntryBrowserRequest request() {
        List<EntryBrowserRequest.Entry> entries = plugin.getMarketService().getMarketData().getRewards().values().stream()
                .filter(reward -> matchesFilter(reward, filter))
                .map(reward -> EntryBrowserRequest.Entry.of(reward.getId(), createRewardItem(reward)))
                .toList();
        return EntryBrowserRequest.builder()
                .title(plugin.title("rewards"))
                .entries(entries)
                .page(page)
                .filter(filter)
                .buttons(buttons)
                .showBack(true)
                .addButton(item(Material.ANVIL, "#03fc88Create Reward", "#ffffffClick to create a new reward set."))
                .context(new RewardBrowserContext())
                .build();
    }

    private boolean matchesFilter(RewardDefinition reward, String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || reward.getId().toLowerCase(Locale.ROOT).contains(normalized)
                || reward.getDisplayName().toLowerCase(Locale.ROOT).contains(normalized)
                || reward.getRarityId().toLowerCase(Locale.ROOT).contains(normalized);
    }

    private void openSearchInput(int returnPage, String currentFilter) {
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of("Search reward ids, names, or rarities."),
                "Search",
                currentFilter,
                "Reward id or name",
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
                value -> new RewardListGui(plugin, viewer, 0, value).open(),
                () -> new RewardListGui(plugin, viewer, returnPage, currentFilter).open()
        );
    }

    private void openCreateReward(int returnPage, String returnFilter) {
        EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                viewer,
                editorTextRequest("Enter a new reward id.", ""),
                input -> {
                    String id = sanitizeId(input);
                    if (id.isBlank()) {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Reward ids must contain letters or numbers."));
                    } else if (plugin.getMarketService().getMarketData().getRewards().containsKey(id)) {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That reward id already exists."));
                    } else {
                        String defaultRarity = plugin.getMarketService().getMarketData().getRarities().keySet().stream().findFirst().orElse("common");
                        plugin.getMarketService().getMarketData().getRewards().put(id, new RewardDefinition(
                                id,
                                "#03fc88New Reward",
                                defaultRarity,
                                1.0D,
                                1,
                                StockMode.SERVER_WIDE,
                                MarketRepository.createNamedItem(Material.CHEST, "#03fc88New Reward Icon", List.of("#ffffffDrag or click a display item into the editor.")),
                                MarketRepository.createNamedItem(Material.DIAMOND, "#03fc88New Reward Item", List.of("#ffffffDrag or click the reward item into the editor.")),
                                List.of(),
                                new CostDefinition(CostType.MONEY, 0.0D, null)
                        ));
                        plugin.getMarketService().persistRewardChange(null, id);
                        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", id));
                        runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, id));
                        return;
                    }
                    runForViewer(() -> new RewardListGui(plugin, viewer, returnPage, returnFilter).open());
                },
                () -> new RewardListGui(plugin, viewer, returnPage, returnFilter).open()
        );
    }

    private ItemStack createRewardItem(RewardDefinition reward) {
        ItemStack item = reward.getIcon();
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.CHEST);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(
                    "§fID: §a" + reward.getId(),
                    "§fRarity: §a" + reward.getRarityId(),
                    "§fChance: §a" + reward.getChance(),
                    "§fStock: §a" + reward.getStock(),
                    "§fStock Mode: §a" + reward.getStockMode().getDisplayName(),
                    "§fCost Type: §a" + reward.getCost().getType().name(),
                    "§fCost: " + plugin.getMarketService().describeCost(reward),
                    "§aClick to edit."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String sanitizeId(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_").trim();
    }

    private record RewardBrowserContext() {
    }
}
