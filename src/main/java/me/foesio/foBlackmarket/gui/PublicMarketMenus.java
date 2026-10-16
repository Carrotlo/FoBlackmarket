package me.foesio.foBlackmarket.gui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.foesio.core.gui.publicgui.GuiLoadReport;
import me.foesio.core.gui.publicgui.PublicGuiClick;
import me.foesio.core.gui.publicgui.PublicGuiContract;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiInventoryHolder;
import me.foesio.core.gui.publicgui.PublicGuiItemRenderer;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiRenderer;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.ActiveOffer;
import me.foesio.foBlackmarket.market.MarketSnapshot;
import me.foesio.foBlackmarket.market.PurchaseResult;
import me.foesio.foBlackmarket.market.RarityDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Core-owned public menus. The plugin supplies live offers and handles gameplay actions. */
public final class PublicMarketMenus implements AutoCloseable {

    public static final String PLAYER = "blackmarket.player";
    public static final String COMMAND = "blackmarket.command";
    private static final String PURCHASE_BUCKET = "market-purchase";
    private static final String FAILURE_BUCKET = "market-failure-feedback";

    private final FoBlackmarket plugin;
    private final PublicGuiService guis;
    private final Map<UUID, BrowserState> browsers = new HashMap<>();

    public PublicMarketMenus(FoBlackmarket plugin) {
        this.plugin = plugin;
        this.guis = new PublicGuiService(plugin)
            .menu(PLAYER, plugin.getDataFolder().toPath().resolve("guis/player-market.yml"), playerContract())
            .menu(COMMAND, plugin.getDataFolder().toPath().resolve("guis/command-market.yml"), commandContract())
            .requireEntry(PLAYER, "offers", 0)
            .requireState(PLAYER, "fallback-offer")
            .requireEntry(COMMAND, "rarities", 0)
            .requireEntry(COMMAND, "previous-rarity-page", 1)
            .requireEntry(COMMAND, "next-rarity-page", 1)
            .requireEntry(COMMAND, "offers", 0)
            .requireEntry(COMMAND, "previous-page", 1)
            .requireEntry(COMMAND, "next-page", 1)
            .requireState(COMMAND, "fallback-offer")
            .requireState(COMMAND, "fallback-rarity")
            .action("blackmarket.purchase", this::purchase)
            .action("blackmarket.select-rarity", this::selectRarity)
            .action("blackmarket.previous-rarity-page", click -> changeRarityPage(click, -1))
            .action("blackmarket.next-rarity-page", click -> changeRarityPage(click, 1))
            .action("blackmarket.previous-page", click -> changePage(click, -1))
            .action("blackmarket.next-page", click -> changePage(click, 1))
            .entryPoint("npc-or-admin", PLAYER)
            .entryPoint("command:/blackmarket", COMMAND);
        guis.start(definitions -> plugin.getMarketService().stagePublicGuiDependency(definitions));
    }

    public static PublicGuiContract playerContract() {
        return new PublicGuiContract(Set.of("blackmarket.purchase"), Map.of(), Set.of(
            "time_remaining", "reward", "rarity", "rarity_plain", "rarity_upper", "stock", "cost", "chance",
            "status_line"), PublicGuiContract.InteractionMode.CORE);
    }

    public static PublicGuiContract commandContract() {
        return new PublicGuiContract(Set.of("blackmarket.select-rarity", "blackmarket.previous-rarity-page",
            "blackmarket.next-rarity-page", "blackmarket.previous-page",
            "blackmarket.next-page"), Map.of(), Set.of("time_remaining", "selected_rarity",
            "showing_offers", "offer_count", "selection_status", "click_line", "reward",
            "rarity", "rarity_plain", "rarity_upper", "stock", "cost", "chance", "commands_line", "status_line",
            "page", "max_page", "rarity_page", "rarity_max_page"), PublicGuiContract.InteractionMode.CORE);
    }

    public GuiLoadReport reload() {
        return guis.reload();
    }

    public GuiLoadReport validateInstalled() {
        return guis.validateInstalled();
    }

    public boolean available() {
        return guis.snapshot() != null;
    }

    public PublicGuiDefinition definition(String menuId) {
        return guis.snapshot().require(menuId);
    }

    public List<Integer> playerOfferSlots() {
        return available() ? definition(PLAYER).entries().get("offers").slots() : List.of();
    }

    public boolean openPlayer(Player viewer) {
        if (!available()) {
            return false;
        }
        guis.openEntryPoint(viewer, "npc-or-admin", playerInput(viewer));
        plugin.getGuiSounds().open(viewer);
        return true;
    }

    public boolean openCommand(Player viewer) {
        if (!available()) {
            return false;
        }
        BrowserState state = new BrowserState();
        state.selectedRarity = plugin.getMarketService().getDefaultRarityId();
        state.rarityPage = pageForRarity(state.selectedRarity,
            plugin.getMarketService().getRarities(), definition(COMMAND).entries().get("rarities").slots().size());
        browsers.put(viewer.getUniqueId(), state);
        guis.openEntryPoint(viewer, "command:/blackmarket", commandInput(viewer, state));
        renderCommandPreviews(viewer);
        plugin.getGuiSounds().open(viewer);
        return true;
    }

    public boolean isViewing(Player player, String menuId) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof PublicGuiInventoryHolder holder
            && holder.frame().definition().menuId().equals(menuId)
            && available() && holder.frame().revision() == guis.snapshot().revision();
    }

    public void forget(Player viewer) {
        browsers.remove(viewer.getUniqueId());
    }

    public void refreshAll() {
        if (!available()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof PublicGuiInventoryHolder holder)) {
                browsers.remove(viewer.getUniqueId());
                continue;
            }
            String menuId = holder.frame().definition().menuId();
            if (!PLAYER.equals(menuId) && !COMMAND.equals(menuId)) {
                continue;
            }
            try {
                BrowserState state = browsers.computeIfAbsent(viewer.getUniqueId(), ignored -> new BrowserState());
                PublicGuiRenderInput input = PLAYER.equals(menuId) ? playerInput(viewer) : commandInput(viewer, state);
                PublicGuiFrame next = guis.plan(menuId, input);
                if (holder.frame().revision() != next.revision()
                    || !holder.frame().bindings().equals(next.bindings())
                    || top.getSize() != next.definition().size()) {
                    guis.open(viewer, menuId, input);
                    if (COMMAND.equals(menuId)) renderCommandPreviews(viewer);
                } else {
                    PublicGuiRenderer.renderInto(viewer, top, withoutRetiredCommandLines(next));
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not refresh public market for " + viewer.getName() + ": " + exception.getMessage());
            }
        }
    }

    private PublicGuiRenderInput playerInput(Player viewer) {
        Map<String, String> globals = globals();
        globals.put("time_remaining", plugin.getConfigManager().formatDuration(
            plugin.getMarketService().getTimeUntilRestock()));
        Map<Integer, PublicGuiRenderInput.EntryValue> entries = new LinkedHashMap<>();
        MarketSnapshot snapshot = plugin.getMarketService().getSnapshot();
        if (snapshot != null) {
            for (int slot : playerOfferSlots()) {
                ActiveOffer offer = snapshot.getOffersBySlot().get(slot);
                if (offer != null) {
                    entries.put(slot, offerValue(viewer, offer, PLAYER));
                }
            }
        }
        return new PublicGuiRenderInput(globals, Map.of(), Map.of(), Map.of("offers", entries));
    }

    private PublicGuiRenderInput commandInput(Player viewer, BrowserState state) {
        PublicGuiDefinition definition = definition(COMMAND);
        String resolvedSelection = resolvedRarity(state.selectedRarity);
        if (!Objects.equals(state.selectedRarity, resolvedSelection)) {
            state.rarityPage = pageForRarity(resolvedSelection, plugin.getMarketService().getRarities(),
                definition.entries().get("rarities").slots().size());
        }
        state.selectedRarity = resolvedSelection;
        List<ActiveOffer> offers = plugin.getMarketService().getOffersForRarity(state.selectedRarity);
        List<Integer> offerSlots = definition.entries().get("offers").slots();
        int pageSize = offerSlots.size();
        int maxPage = Math.max(0, (offers.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(state.pages.getOrDefault(state.selectedRarity, 0), maxPage));
        state.pages.put(state.selectedRarity, page);
        int start = page * pageSize;
        int count = Math.min(pageSize, Math.max(0, offers.size() - start));

        Map<String, String> globals = globals();
        globals.put("time_remaining", plugin.getConfigManager().formatDuration(
            plugin.getMarketService().getTimeUntilRestock()));
        globals.put("selected_rarity", selectedRarityLabel(state.selectedRarity));
        globals.put("showing_offers", message(offers.isEmpty() ? "gui.showing-no-offers" : "gui.showing-offers",
            Map.of("first_offer", plugin.getConfigManager().formatCount(start + 1),
                "last_offer", plugin.getConfigManager().formatCount(start + count),
                "total_offers", plugin.getConfigManager().formatCount(offers.size()))));

        Map<Integer, PublicGuiRenderInput.EntryValue> rarities = new LinkedHashMap<>();
        List<Integer> raritySlots = definition.entries().get("rarities").slots();
        List<RarityDefinition> availableRarities = plugin.getMarketService().getRarities();
        int maxRarityPage = Math.max(0, (availableRarities.size() - 1) / raritySlots.size());
        state.rarityPage = Math.max(0, Math.min(state.rarityPage, maxRarityPage));
        int rarityStart = state.rarityPage * raritySlots.size();
        for (int index = 0; index < Math.min(raritySlots.size(), availableRarities.size() - rarityStart); index++) {
            RarityDefinition rarity = availableRarities.get(rarityStart + index);
            boolean selected = rarity.getId().equals(state.selectedRarity);
            String label = rarity.getDisplayName();
            Map<String, String> values = rarityPlaceholders(label);
            values.put("offer_count", plugin.getConfigManager().formatCount(
                plugin.getMarketService().getOffersForRarity(rarity.getId()).size()));
            values.put("selection_status", message(selected ? "gui.rarity-selected" : "gui.rarity-unselected", Map.of()));
            values.put("click_line", selected ? message("gui.rarity-selected-click-line", Map.of())
                : message("gui.rarity-unselected-click-line", rarityPlaceholders(label)));
            ItemStack icon = rarity.getIcon();
            if (icon == null || icon.getType().isAir()) {
                icon = fallback(viewer, COMMAND, "fallback-rarity", Map.of("rarity", label));
            }
            rarities.put(raritySlots.get(index), new PublicGuiRenderInput.EntryValue(rarity.getId(), icon, values));
        }

        Map<Integer, PublicGuiRenderInput.EntryValue> previews = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ActiveOffer offer = offers.get(start + index);
            previews.put(offerSlots.get(index), offerValue(viewer, offer, COMMAND));
        }

        Map<String, Map<Integer, PublicGuiRenderInput.EntryValue>> entries = new LinkedHashMap<>();
        entries.put("rarities", rarities);
        entries.put("offers", previews);
        Map<String, String> rarityPageValues = Map.of(
            "rarity_page", plugin.getConfigManager().formatCount(state.rarityPage + 1),
            "rarity_max_page", plugin.getConfigManager().formatCount(maxRarityPage + 1));
        if (state.rarityPage > 0) {
            int slot = definition.entries().get("previous-rarity-page").slots().getFirst();
            entries.put("previous-rarity-page", Map.of(slot,
                new PublicGuiRenderInput.EntryValue("previous-rarities", null, rarityPageValues)));
        }
        if (state.rarityPage < maxRarityPage) {
            int slot = definition.entries().get("next-rarity-page").slots().getFirst();
            entries.put("next-rarity-page", Map.of(slot,
                new PublicGuiRenderInput.EntryValue("next-rarities", null, rarityPageValues)));
        }
        Map<String, String> pageValues = Map.of("page", plugin.getConfigManager().formatCount(page + 1),
            "max_page", plugin.getConfigManager().formatCount(maxPage + 1));
        if (page > 0) {
            int slot = definition.entries().get("previous-page").slots().getFirst();
            entries.put("previous-page", Map.of(slot,
                new PublicGuiRenderInput.EntryValue("previous", null, pageValues)));
        }
        if (page < maxPage) {
            int slot = definition.entries().get("next-page").slots().getFirst();
            entries.put("next-page", Map.of(slot,
                new PublicGuiRenderInput.EntryValue("next", null, pageValues)));
        }
        return new PublicGuiRenderInput(globals, Map.of(), Map.of(), entries);
    }

    private PublicGuiRenderInput.EntryValue offerValue(Player viewer, ActiveOffer offer, String menuId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("reward", offer.getReward().getDisplayName());
        values.putAll(rarityPlaceholders(offer.getRarity().getDisplayName()));
        int stock = offer.getRemainingStock(viewer.getUniqueId());
        values.put("stock", plugin.getConfigManager().formatCount(stock));
        values.put("cost", plugin.getMarketService().describePublicCost(offer.getReward()));
        values.put("chance", plugin.getMarketService().formatChance(offer.getReward().getChance()));
        if (COMMAND.equals(menuId)) {
            values.put("commands_line", ""); // Legacy installed YAML can still refer to this retired token.
        }
        values.put("status_line", message(PLAYER.equals(menuId)
            ? (stock > 0 ? "gui.player-buy-line" : "gui.player-sold-out-line")
            : "gui.command-preview-line", Map.of()));
        ItemStack icon = offer.getReward().getIcon();
        if (icon == null || icon.getType().isAir()) {
            icon = fallback(viewer, menuId, "fallback-offer", Map.of("reward", values.get("reward")));
        }
        return new PublicGuiRenderInput.EntryValue(offer.getRewardId(), icon, values);
    }

    /** Retire the old public command-count row even in already-installed GUI files. */
    static PublicGuiFrame withoutRetiredCommandLines(PublicGuiFrame frame) {
        if (!COMMAND.equals(frame.definition().menuId())) return frame;
        Map<Integer, PublicGuiFrame.Visual> visuals = new LinkedHashMap<>(frame.visuals());
        boolean changed = false;
        for (Map.Entry<Integer, PublicGuiFrame.Visual> entry : frame.visuals().entrySet()) {
            PublicGuiFrame.Visual visual = entry.getValue();
            if (!"entries.offers".equals(visual.path()) || visual.sourceConfig() == null) continue;
            PublicGuiDefinition.SourceItem source = visual.sourceConfig();
            PublicGuiDefinition.LoreEdit lore = source.lore();
            List<String> lines = lore.lines().stream()
                .filter(line -> !line.contains("{commands_line}"))
                .toList();
            if (lines.size() == lore.lines().size()) continue;
            PublicGuiDefinition.SourceItem adjusted = new PublicGuiDefinition.SourceItem(
                source.materialOverride(), new PublicGuiDefinition.LoreEdit(lore.mode(), lines), source.model());
            visuals.put(entry.getKey(), new PublicGuiFrame.Visual(null, adjusted, visual.source(),
                visual.placeholders(), visual.cycle(), visual.selection(), visual.path()));
            changed = true;
        }
        return changed ? new PublicGuiFrame(frame.definition(), frame.revision(), frame.title(),
            visuals, frame.bindings(), frame.reservedSlots()) : frame;
    }

    private void renderCommandPreviews(Player viewer) {
        Inventory top = viewer.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof PublicGuiInventoryHolder holder) {
            PublicGuiRenderer.renderInto(viewer, top, withoutRetiredCommandLines(holder.frame()));
        }
    }

    private ItemStack fallback(Player viewer, String menuId, String state, Map<String, String> values) {
        Map<String, String> placeholders = globals();
        placeholders.putAll(values);
        return PublicGuiItemRenderer.fixed(viewer, definition(menuId).states().get(state),
            placeholders, null, null, "states." + state);
    }

    private Map<String, String> globals() {
        return new LinkedHashMap<>(plugin.getMessages().tokenValues());
    }

    private String message(String path, Map<String, String> values) {
        return plugin.getMessages().render(path, "", values);
    }

    private String selectedRarityLabel(String selectedId) {
        for (RarityDefinition rarity : plugin.getMarketService().getRarities()) {
            if (rarity.getId().equals(selectedId)) {
                return rarity.getDisplayName();
            }
        }
        return message("gui.no-rarity", Map.of());
    }

    private String resolvedRarity(String current) {
        return resolveRarity(current, plugin.getMarketService().getRarities(),
            plugin.getMarketService().getDefaultRarityId());
    }

    static String resolveRarity(String current, List<RarityDefinition> rarities, String fallback) {
        if (current != null && rarities.stream().anyMatch(rarity -> rarity.getId().equals(current))) {
            return current;
        }
        return fallback == null ? "" : fallback;
    }

    static int pageForRarity(String selected, List<RarityDefinition> rarities, int pageSize) {
        if (selected == null || pageSize <= 0) return 0;
        for (int index = 0; index < rarities.size(); index++) {
            if (selected.equals(rarities.get(index).getId())) return index / pageSize;
        }
        return 0;
    }

    static Map<String, String> rarityPlaceholders(String label) {
        String plain = FoText.stripColor(label);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("rarity", label);
        values.put("rarity_plain", plain);
        values.put("rarity_upper", plain.toUpperCase(Locale.ROOT));
        return values;
    }

    private void purchase(PublicGuiClick click) {
        Player viewer = click.player();
        ActiveOffer offer = plugin.getMarketService().getOffer(click.rawSlot());
        if (offer == null || !offer.getRewardId().equals(click.entryId())) {
            return;
        }
        if (!plugin.getActionCooldowns().tryAcquire(viewer.getUniqueId(), PURCHASE_BUCKET,
            plugin.getConfigManager().getPurchaseCooldownMillis())) {
            return;
        }
        PurchaseResult result = plugin.getMarketService().purchase(viewer, offer.getRewardId());
        switch (result.getStatus()) {
            case SUCCESS -> {
                plugin.getMessages().send(viewer, "messages.market-bought", "", Map.of(
                    "reward", result.getRewardName(), "stock",
                    plugin.getConfigManager().formatCount(result.getRemainingStock())));
                plugin.getSounds().play(viewer, "market.purchase-success");
            }
            case SOLD_OUT -> failed(viewer, "market-sold-out", Map.of());
            case NOT_ENOUGH_MONEY -> failed(viewer, "market-not-enough-money", Map.of("price", result.getPriceText()));
            case NOT_ENOUGH_ITEMS -> failed(viewer, "market-not-enough-items", Map.of());
            case INVENTORY_FULL -> failed(viewer, "market-inventory-full", Map.of());
            default -> failed(viewer, "market-purchase-failed", Map.of());
        }
        refreshAll();
    }

    private void failed(Player viewer, String key, Map<String, String> values) {
        if (!plugin.getActionCooldowns().tryAcquire(viewer.getUniqueId(), FAILURE_BUCKET,
            plugin.getConfigManager().getFailedPurchaseFeedbackCooldownMillis())) {
            return;
        }
        plugin.getSounds().play(viewer, "market.purchase-failure");
        if (plugin.getConfigManager().shouldSendFailedPurchaseFeedback()) {
            plugin.getMessages().send(viewer, "messages." + key, "", values);
        }
    }

    private void selectRarity(PublicGuiClick click) {
        BrowserState state = browsers.get(click.player().getUniqueId());
        if (state == null || click.entryId().equals(state.selectedRarity)) {
            return;
        }
        boolean exists = plugin.getMarketService().getRarities().stream().anyMatch(r -> r.getId().equals(click.entryId()));
        if (!exists) {
            return;
        }
        state.selectedRarity = click.entryId();
        guis.open(click.player(), COMMAND, commandInput(click.player(), state));
        renderCommandPreviews(click.player());
        plugin.getGuiSounds().select(click.player());
    }

    private void changePage(PublicGuiClick click, int delta) {
        BrowserState state = browsers.get(click.player().getUniqueId());
        if (state == null) {
            return;
        }
        int oldPage = state.pages.getOrDefault(state.selectedRarity, 0);
        state.pages.put(state.selectedRarity, Math.max(0, oldPage + delta));
        guis.open(click.player(), COMMAND, commandInput(click.player(), state));
        renderCommandPreviews(click.player());
        if (delta < 0) {
            plugin.getGuiSounds().previousPage(click.player());
        } else {
            plugin.getGuiSounds().nextPage(click.player());
        }
    }

    private void changeRarityPage(PublicGuiClick click, int delta) {
        BrowserState state = browsers.get(click.player().getUniqueId());
        if (state == null) return;
        int pageSize = definition(COMMAND).entries().get("rarities").slots().size();
        int maxPage = Math.max(0, (plugin.getMarketService().getRarities().size() - 1) / pageSize);
        int next = Math.max(0, Math.min(maxPage, state.rarityPage + delta));
        if (next == state.rarityPage) return;
        state.rarityPage = next;
        guis.open(click.player(), COMMAND, commandInput(click.player(), state));
        renderCommandPreviews(click.player());
        if (delta < 0) plugin.getGuiSounds().previousPage(click.player());
        else plugin.getGuiSounds().nextPage(click.player());
    }

    @Override
    public void close() {
        guis.close();
        browsers.clear();
    }

    private static final class BrowserState {
        private String selectedRarity = "";
        private int rarityPage;
        private final Map<String, Integer> pages = new HashMap<>();
    }
}
