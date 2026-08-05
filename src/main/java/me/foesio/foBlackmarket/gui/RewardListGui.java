package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.EditorDialogInputs;
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

    private static final int ROWS = 6;
    private static final int[] ENTRY_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34
    };
    private static final int PAGE_SIZE = ENTRY_SLOTS.length;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CREATE_SLOT = 47;
    private static final int NEXT_SLOT = 53;

    private final int page;
    private List<RewardDefinition> rewards;

    public RewardListGui(FoBlackmarket plugin, Player viewer, int page) {
        super(plugin, viewer, 54, plugin.title("rewards"));
        this.page = Math.max(0, page);
    }

    @Override
    protected void render() {
        rewards = new ArrayList<>(plugin.getMarketService().getMarketData().getRewards().values());
        int start = page * PAGE_SIZE;
        for (int index = 0; index < ENTRY_SLOTS.length; index++) {
            ItemStack item;
            int rewardIndex = start + index;
            if (rewardIndex < rewards.size()) {
                item = createRewardItem(rewards.get(rewardIndex));
            } else {
                item = emptyRewardSlot();
            }
            inventory.setItem(ENTRY_SLOTS[index], item);
        }

        int maxPage = maxPage(rewards.size());
        if (page > 0) {
            inventory.setItem(PREVIOUS_SLOT, buttons.previousPage(page, maxPage));
        }
        inventory.setItem(CREATE_SLOT, item(Material.ANVIL, "#03fc88Create Reward", "#ffffffClick to create a new reward set."));
        if (page < maxPage) {
            inventory.setItem(NEXT_SLOT, buttons.nextPage(page, maxPage));
        }
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back());
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        int entryIndex = entryIndex(slot);
        if (entryIndex >= 0) {
            int index = page * PAGE_SIZE + entryIndex;
            if (index < rewards.size()) {
                plugin.getGuiManager().openRewardEditor(viewer, rewards.get(index).getId());
            }
            return;
        }

        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                plugin.getGuiManager().openRewardList(viewer, page - 1);
            }
            return;
        }
        if (slot == bottomMiddleSlot(ROWS)) {
            plugin.getGuiManager().openMainEditor(viewer);
            return;
        }
        if (slot == CREATE_SLOT) {
            EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorTextRequest("Enter a new reward id.", ""), input -> {
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
                runForViewer(() -> plugin.getGuiManager().openRewardList(viewer, page));
            }, () -> {
                plugin.getMessages().send(viewer, "messages.editor-cancelled");
                plugin.getGuiManager().openRewardList(viewer, page);
            });
            return;
        }
        if (slot == NEXT_SLOT) {
            if ((page + 1) * PAGE_SIZE < rewards.size()) {
                plugin.getGuiManager().openRewardList(viewer, page + 1);
            }
        }
    }

    private int maxPage(int totalEntries) {
        return Math.max(0, (Math.max(0, totalEntries) - 1) / PAGE_SIZE);
    }

    private int entryIndex(int slot) {
        for (int index = 0; index < ENTRY_SLOTS.length; index++) {
            if (ENTRY_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private ItemStack createRewardItem(RewardDefinition reward) {
        ItemStack item = reward.getIcon();
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.CHEST);
        } else {
            item = item.clone();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§fID: §a" + reward.getId());
            lore.add("§fRarity: §a" + reward.getRarityId());
            lore.add("§fChance: §a" + reward.getChance());
            lore.add("§fStock: §a" + reward.getStock());
            lore.add("§fStock Mode: §a" + reward.getStockMode().getDisplayName());
            lore.add("§fCost Type: §a" + reward.getCost().getType().name());
            lore.add("§fCost: " + plugin.getMarketService().describeCost(reward));
            lore.add("§aClick to edit.");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack emptyRewardSlot() {
        return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ");
    }

    private String sanitizeId(String input) {
        return input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_").trim();
    }
}
