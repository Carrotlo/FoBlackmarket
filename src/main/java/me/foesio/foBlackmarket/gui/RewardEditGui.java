package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.market.CostType;
import me.foesio.foBlackmarket.market.RewardDefinition;
import me.foesio.foBlackmarket.market.StockMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class RewardEditGui extends BaseGui {

    private static final int ROWS = 4;
    private final String rewardId;

    public RewardEditGui(FoBlackmarket plugin, Player viewer, String rewardId) {
        super(plugin, viewer, 36, plugin.title("reward-edit"));
        this.rewardId = rewardId;
    }

    @Override
    protected void render() {
        RewardDefinition reward = getReward();
        if (reward == null) {
            plugin.getGuiManager().openRewardList(viewer, 0);
            return;
        }

        inventory.setItem(10, item(Material.NAME_TAG, "#03fc88Reward ID", "#ffffffCurrent: #03fc88" + reward.getId(), "#ffffffClick to rename."));
        inventory.setItem(11, item(Material.WRITABLE_BOOK, "#03fc88Display Name", "#ffffffCurrent: " + reward.getDisplayName(), "#ffffffClick to edit."));
        inventory.setItem(12, createRarityButton(reward));
        inventory.setItem(13, item(Material.GOLD_NUGGET, "#03fc88Chance", "#ffffffCurrent: #03fc88" + reward.getChance(), "#ffffffClick to edit."));
        inventory.setItem(14, item(Material.BARREL, "#03fc88Stock", "#ffffffCurrent: #03fc88" + reward.getStock(), "#ffffffClick to edit."));

        inventory.setItem(15, createCostTypeButton(reward));
        inventory.setItem(16, createCostButton(reward));

        inventory.setItem(19, createDisplayIconButton(reward));
        inventory.setItem(20, createRewardItemButton(reward));
        inventory.setItem(21, createCommandButton(reward));
        inventory.setItem(22, item(
            Material.BOOK,
            "#03fc88Preview",
            "#ffffffRarity: #03fc88" + reward.getRarityId(),
            "#ffffffStock Mode: #03fc88" + reward.getStockMode().getDisplayName(),
            "#ffffffCost: #03fc88" + plugin.getMarketService().describeCost(reward),
            "#ffffffCommands: #03fc88" + reward.getCommands().size()
        ));
        inventory.setItem(23, createStockModeButton(reward));
        inventory.setItem(24, item(Material.LAVA_BUCKET, "#ff5d73Delete Reward", "#ffffffShift-click to delete this reward set."));
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back());
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        RewardDefinition reward = getReward();
        if (reward == null) {
            plugin.getGuiManager().openRewardList(viewer, 0);
            return;
        }

        switch (event.getRawSlot()) {
            case 10 -> promptRenameId(reward);
            case 11 -> promptString("Enter the reward display name.", reward.getDisplayName(), value -> reward.setDisplayName(value));
            case 12 -> {
                List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
                if (!rarityIds.isEmpty()) {
                    int current = Math.max(0, rarityIds.indexOf(reward.getRarityId()));
                    reward.setRarityId(rarityIds.get((current + 1) % rarityIds.size()));
                    saveAndRefresh("reward rarity");
                }
            }
            case 13 -> promptDouble("Enter the reward chance.", String.valueOf(reward.getChance()), reward::setChance, "Use a number like 25 or 12.5.");
            case 14 -> promptInt("Enter the stock amount.", String.valueOf(reward.getStock()), reward::setStock, "Use a whole number.");
            case 15 -> {
                reward.getCost().setType(nextCostType(reward.getCost().getType()));
                saveAndRefresh("reward cost type");
            }
            case 16 -> {
                if (reward.getCost().getType() == CostType.MONEY) {
                    promptMoney("Enter the money cost.", String.valueOf(reward.getCost().getMoneyCost()), reward.getCost()::setMoneyCost, "Use a number like 500 or 500.0.");
                } else if (reward.getCost().getType() == CostType.ITEM) {
                    if (event.getClick() == ClickType.RIGHT) {
                        reward.getCost().setItemCost(null);
                        saveAndRefresh("reward item cost");
                        return;
                    }
                    ItemStack item = inputItem(event.getCursor());
                    if (item == null) {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold the cost item in your main hand first."));
                        return;
                    }
                    reward.getCost().setItemCost(item);
                    saveAndRefresh("reward item cost");
                } else if (event.getClick() == ClickType.RIGHT) {
                    reward.getCost().setItemCost(null);
                    saveAndRefresh("reward item cost");
                } else {
                    ItemStack item = inputItem(event.getCursor());
                    if (item != null) {
                        reward.getCost().setItemCost(item);
                        saveAndRefresh("reward item cost");
                        return;
                    }
                    if (event.getClick().isShiftClick()) {
                        reward.getCost().setMoneyCost(0.0D);
                        saveAndRefresh("reward money cost");
                        return;
                    }
                    if (viewer.getInventory().getItemInMainHand().getType().isAir()) {
                        promptMoney("Enter the money cost.", String.valueOf(reward.getCost().getMoneyCost()), reward.getCost()::setMoneyCost, "Use a number like 500 or 500.0.");
                    } else {
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "For BOTH costs: click with an item to set the item cost, or empty your hand and click to edit money."));
                        return;
                    }
                }
            }
            case 19 -> assignTopItem(value -> reward.setIcon(value), "reward icon", event);
            case 20 -> assignTopItem(value -> reward.setRewardItem(value), "reward item", event);
            case 21 -> promptCommands(reward);
            case 23 -> {
                reward.setStockMode(reward.getStockMode().next());
                saveAndRefresh("reward stock mode");
            }
            case 24 -> {
                if (event.getClick().isShiftClick()) {
                    plugin.getMarketService().getMarketData().getRewards().remove(reward.getId());
                    plugin.getMarketService().persistRewardRemoval(reward.getId());
                    plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward deletion"));
                    plugin.getGuiManager().openRewardList(viewer, 0);
                }
            }
            case 31 -> plugin.getGuiManager().openRewardList(viewer, 0);
            default -> {
            }
        }
    }

    @Override
    public void handleDrag(InventoryDragEvent event) {
        RewardDefinition reward = getReward();
        ItemStack dragged = inputItem(event.getOldCursor());
        if (reward == null || dragged == null) {
            return;
        }

        if (event.getRawSlots().contains(19)) {
            reward.setIcon(dragged);
            saveAndRefresh("reward icon");
            return;
        }
        if (event.getRawSlots().contains(20)) {
            reward.setRewardItem(dragged);
            saveAndRefresh("reward item");
            return;
        }
        if ((reward.getCost().getType() == CostType.ITEM || reward.getCost().getType() == CostType.BOTH) && event.getRawSlots().contains(16)) {
            reward.getCost().setItemCost(dragged);
            saveAndRefresh("reward item cost");
        }
    }

    @Override
    public boolean allowsPlayerInventoryInteraction() {
        return true;
    }

    private RewardDefinition getReward() {
        return plugin.getMarketService().getMarketData().getRewards().get(rewardId);
    }

    private void promptRenameId(RewardDefinition reward) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorTextRequest("Enter the new reward id.", reward.getId()), input -> {
            String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_").trim();
            if (id.isBlank()) {
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Reward ids must contain letters or numbers."));
            } else if (!id.equalsIgnoreCase(reward.getId()) && plugin.getMarketService().getMarketData().getRewards().containsKey(id)) {
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That reward id already exists."));
            } else {
                String previousId = reward.getId();
                plugin.getMarketService().getMarketData().getRewards().remove(previousId);
                reward.setId(id);
                plugin.getMarketService().getMarketData().getRewards().put(id, reward);
                plugin.getMarketService().persistRewardChange(previousId, id);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward id"));
                runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, id));
                return;
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, reward.getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, reward.getId());
        });
    }

    private void promptString(String prompt, String currentValue, java.util.function.Consumer<String> consumer) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorTextRequest(prompt, currentValue), input -> {
            consumer.accept(input);
            persistCurrentRewardChange();
            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, getReward().getId());
        });
    }

    private void promptDouble(String prompt, String currentValue, java.util.function.DoubleConsumer consumer, String invalidReason) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorNumberRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Double.parseDouble(input.trim()));
                persistCurrentRewardChange();
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, getReward().getId());
        });
    }

    private void promptMoney(String prompt, String currentValue, java.util.function.DoubleConsumer consumer, String invalidReason) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorMoneyRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Double.parseDouble(input.trim()));
                persistCurrentRewardChange();
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, getReward().getId());
        });
    }

    private void promptInt(String prompt, String currentValue, java.util.function.IntConsumer consumer, String invalidReason) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorNumberRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Integer.parseInt(input.trim()));
                persistCurrentRewardChange();
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, getReward().getId());
        });
    }

    private void promptCommands(RewardDefinition reward) {
        EditorDialogInputs.openTextFromInventory(plugin, plugin.getCore().inventoryCloseSuppressor(), plugin.getCore().dialogService(), viewer, editorCommandRequest(
            "Enter commands separated by |. Type clear to remove all commands.",
            String.join(" | ", reward.getCommands()),
            "give {player} diamond 1 | say hi"
        ), input -> {
            if ("clear".equalsIgnoreCase(input.trim())) {
                reward.setCommands(List.of());
            } else {
                List<String> commands = new ArrayList<>();
                for (String part : input.split("\\|")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        commands.add(trimmed);
                    }
                }
                reward.setCommands(commands);
            }
            plugin.getMarketService().persistRewardChange(reward.getId(), reward.getId());
            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward commands"));
            runForViewer(() -> plugin.getGuiManager().openRewardEditor(viewer, reward.getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditor(viewer, reward.getId());
        });
    }

    private void assignTopItem(java.util.function.Consumer<ItemStack> consumer, String target, InventoryClickEvent event) {
        if (event.getClick() == ClickType.RIGHT) {
            consumer.accept(null);
            saveAndRefresh(target);
            return;
        }

        ItemStack item = inputItem(event.getCursor());
        if (item == null) {
            plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold an item in your main hand first."));
            return;
        }
        consumer.accept(item);
        saveAndRefresh(target);
    }

    private void persistCurrentRewardChange() {
        RewardDefinition reward = getReward();
        if (reward != null) {
            plugin.getMarketService().persistRewardChange(reward.getId(), reward.getId());
        }
    }

    private void saveAndRefresh(String target) {
        persistCurrentRewardChange();
        plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", target));
        plugin.getGuiManager().openRewardEditor(viewer, getReward().getId());
    }

    private ItemStack createDisplayIconButton(RewardDefinition reward) {
        return createEditorItemButton(Material.CHEST, "#03fc88Display Icon", reward.getIcon(), false);
    }

    private ItemStack createRewardItemButton(RewardDefinition reward) {
        return createEditorItemButton(Material.DIAMOND, "#03fc88Reward Item", reward.getRewardItem(), false);
    }

    private ItemStack createRarityButton(RewardDefinition reward) {
        List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
        List<String> lore = new ArrayList<>();
        lore.add("#ffffffCurrent: #03fc88" + reward.getRarityId());
        lore.add("#ffffffClick to cycle rarities.");
        if (rarityIds.isEmpty()) {
            lore.add("#a7b8b0No rarities configured.");
        } else {
            lore.addAll(CycleOptions.lore(
                reward.getRarityId(),
                rarityIds.stream()
                    .map(id -> new CycleOption(id, id))
                    .toList()
            ));
        }
        return item(Material.NETHER_STAR, "#03fc88Rarity", lore);
    }

    private ItemStack createCostTypeButton(RewardDefinition reward) {
        CostType type = reward.getCost().getType();
        List<String> lore = new ArrayList<>();
        String selected = costTypeDisplayName(type);
        lore.add("#ffffffCurrent: #03fc88" + selected);
        lore.add("#ffffffClick to cycle.");
        lore.addAll(CycleOptions.lore(type.name(), List.of(
            new CycleOption(CostType.MONEY.name(), "Money"),
            new CycleOption(CostType.ITEM.name(), "Item"),
            new CycleOption(CostType.BOTH.name(), "Both")
        )));
        return item(costTypeMaterial(type), "#03fc88Cost Type", lore);
    }

    private ItemStack createStockModeButton(RewardDefinition reward) {
        StockMode stockMode = reward.getStockMode();
        List<String> lore = new ArrayList<>();
        lore.add("#ffffffCurrent: #03fc88" + stockMode.getDisplayName());
        lore.add("#ffffffClick to cycle.");
        lore.addAll(CycleOptions.lore(stockMode.name(), List.of(
            new CycleOption(StockMode.SERVER_WIDE.name(), StockMode.SERVER_WIDE.getDisplayName()),
            new CycleOption(StockMode.PER_PLAYER.name(), StockMode.PER_PLAYER.getDisplayName())
        )));
        return item(stockMode == StockMode.SERVER_WIDE ? Material.ENDER_CHEST : Material.PLAYER_HEAD, "#03fc88Stock Mode", lore);
    }

    private ItemStack createCostButton(RewardDefinition reward) {
        if (reward.getCost().getType() == CostType.MONEY) {
            return item(
                Material.EMERALD,
                "#03fc88Cost Value",
                "#ffffffCurrent: #03fc88" + plugin.getMarketService().describeCost(reward),
                "#ffffffClick to edit the money cost."
            );
        }
        if (reward.getCost().getType() == CostType.ITEM) {
            return createEditorItemButton(Material.ITEM_FRAME, "#03fc88Item Cost", reward.getCost().getItemCost(), true);
        }
        List<String> lore = new ArrayList<>();
        lore.add("#ffffffMoney: #03fc88" + plugin.getMarketService().describeCost(reward).split(" \\+ ")[0]);
        if (reward.getCost().getItemCost() == null || reward.getCost().getItemCost().getType().isAir()) {
            lore.add("#ffffffItem Cost: #a7b8b0Not set");
        } else {
            lore.add("#ffffffItem Cost: #03fc88" + plugin.getMarketService().describeCost(reward).substring(plugin.getMarketService().describeCost(reward).indexOf(" + ") + 3));
        }
        lore.add("#ffffffClick with an item to set the item cost.");
        lore.add("#ffffffClick with empty hand to edit money.");
        lore.add("#ffffffRight click to clear the item cost.");
        lore.add("#ffffffShift-click with empty hand to clear the money cost.");
        return item(Material.CRAFTER, "#03fc88Money + Item Cost", lore);
    }

    private ItemStack createCommandButton(RewardDefinition reward) {
        List<String> lore = new ArrayList<>();
        lore.add("#ffffffCurrent: #03fc88" + reward.getCommands().size());
        lore.add("#ffffffClick to edit with #03fc88|#ffffff separators.");
        lore.add("#ffffffExample: #03fc88give {player} diamond 1 | say hi");
        lore.add("#ffffffExisting:");
        if (reward.getCommands().isEmpty()) {
            lore.add("#a7b8b0- none");
        } else {
            for (String command : reward.getCommands()) {
                lore.add("#ffffff- #a7b8b0" + command);
            }
        }
        return item(Material.COMMAND_BLOCK, "#03fc88Commands", lore);
    }

    private ItemStack createEditorItemButton(Material material, String title, ItemStack current, boolean itemCost) {
        ItemStack preview = current == null || current.getType().isAir() ? new ItemStack(material) : current.clone();
        List<String> lore = new ArrayList<>();
        if (current == null || current.getType().isAir()) {
            lore.add("#ffffffCurrent: #a7b8b0Not set");
        } else {
            lore.add("#ffffffCurrent Material: #03fc88" + current.getType().name());
            lore.add("#ffffffAmount: #03fc88" + current.getAmount());
            ItemMeta currentMeta = current.getItemMeta();
            if (currentMeta != null && currentMeta.hasDisplayName()) {
                lore.add("#ffffffCustom Name: " + currentMeta.getDisplayName());
            } else {
                lore.add("#ffffffCustom Name: #a7b8b0None");
            }
            if (currentMeta != null && currentMeta.hasLore()) {
                lore.add("#ffffffLore Lines: #03fc88" + currentMeta.getLore().size());
            } else {
                lore.add("#ffffffLore Lines: #a7b8b0None");
            }
        }
        lore.add("#ffffffClick or drag an item here to set it.");
        lore.add("#ffffffRight click to clear.");
        if (itemCost) {
            lore.add("#ffffffThis item is consumed on purchase.");
        }
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FoText.color(title));
            java.util.List<String> coloredLore = new java.util.ArrayList<>();
            for (String line : lore) {
                coloredLore.add(FoText.color(line));
            }
            meta.setLore(coloredLore);
            preview.setItemMeta(meta);
        }
        return preview;
    }

    private Material costTypeMaterial(CostType type) {
        return switch (type) {
            case MONEY -> Material.SUNFLOWER;
            case ITEM -> Material.CHEST_MINECART;
            case BOTH -> Material.CRAFTER;
        };
    }

    private String costTypeDisplayName(CostType type) {
        return switch (type) {
            case MONEY -> "Money";
            case ITEM -> "Item";
            case BOTH -> "Both";
        };
    }

    private CostType nextCostType(CostType current) {
        return switch (current) {
            case MONEY -> CostType.ITEM;
            case ITEM -> CostType.BOTH;
            case BOTH -> CostType.MONEY;
        };
    }
}
