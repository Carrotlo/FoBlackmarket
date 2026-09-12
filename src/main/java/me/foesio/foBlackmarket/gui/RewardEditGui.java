package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.number.LargeNumberParser;
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
            plugin.getGuiManager().openRewardListSilently(viewer, 0);
            return;
        }

        inventory.setItem(10, button(Material.NAME_TAG, "#03fc88", "Reward ID",
            List.of("Current: " + reward.getId(), "Click to rename."), "rename the reward"));
        inventory.setItem(11, button(Material.WRITABLE_BOOK, "#03fc88", "Display Name",
            List.of("Current: " + reward.getDisplayName(), "Click to edit."), "edit the display name"));
        inventory.setItem(12, createRarityButton(reward));
        inventory.setItem(13, button(Material.GOLD_NUGGET, "#03fc88", "Chance",
            List.of("Current: " + reward.getChance(), "Click to edit."), "edit the chance"));
        inventory.setItem(14, button(Material.BARREL, "#03fc88", "Stock",
            List.of("Current: " + reward.getStock(), "Click to edit."), "edit the stock"));

        inventory.setItem(15, createCostTypeButton(reward));
        inventory.setItem(16, createCostButton(reward));

        inventory.setItem(19, createDisplayIconButton(reward));
        inventory.setItem(20, createRewardItemButton(reward));
        inventory.setItem(21, createCommandButton(reward));
        inventory.setItem(22, button(Material.BOOK, "#03fc88", "Preview", List.of(
            "Rarity: " + reward.getRarityId(),
            "Stock Mode: " + reward.getStockMode().getDisplayName(),
            "Cost: " + plugin.getMarketService().describeCost(reward),
            "Commands: " + reward.getCommands().size()
        ), "view the preview"));
        inventory.setItem(23, createStockModeButton(reward));
        inventory.setItem(24, button(Material.LAVA_BUCKET, "#ff5d73", "Delete Reward",
            List.of("Shift-click to delete this reward set."), "open confirmation"));
        inventory.setItem(bottomMiddleSlot(ROWS), buttons.back(viewer));
        fillEditorBackground();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        RewardDefinition reward = getReward();
        if (reward == null) {
            plugin.getGuiManager().openRewardListSilently(viewer, 0);
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
                    editorSounds.cycle(viewer);
                    saveAndRefresh("reward rarity");
                } else {
                    editorSounds.error(viewer);
                }
            }
            case 13 -> promptDouble("Enter the reward chance.", String.valueOf(reward.getChance()), reward::setChance, "Use a number like 25 or 12.5.");
            case 14 -> promptInt("Enter the stock amount.", String.valueOf(reward.getStock()), reward::setStock, "Use a whole number.");
            case 15 -> {
                reward.getCost().setType(nextCostType(reward.getCost().getType()));
                editorSounds.cycle(viewer);
                saveAndRefresh("reward cost type");
            }
            case 16 -> {
                if (reward.getCost().getType() == CostType.MONEY) {
                    promptMoney("Enter the money cost.", String.valueOf(reward.getCost().getMoneyCost()), reward.getCost()::setMoneyCost, "Use a number like 500, 1.5k, or 1Qa.");
                } else if (reward.getCost().getType() == CostType.ITEM) {
                    if (event.getClick() == ClickType.RIGHT) {
                        reward.getCost().setItemCost(null);
                        editorSounds.delete(viewer);
                        saveAndRefresh("reward item cost");
                        return;
                    }
                    ItemStack item = inputItem(event.getCursor());
                    if (item == null) {
                        editorSounds.addItemError(viewer);
                        plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold the cost item in your main hand first."));
                        return;
                    }
                    reward.getCost().setItemCost(item);
                    editorSounds.addItem(viewer);
                    saveAndRefresh("reward item cost");
                } else if (event.getClick() == ClickType.RIGHT) {
                    reward.getCost().setItemCost(null);
                    editorSounds.delete(viewer);
                    saveAndRefresh("reward item cost");
                } else {
                    ItemStack item = inputItem(event.getCursor());
                    if (item != null) {
                        reward.getCost().setItemCost(item);
                        editorSounds.addItem(viewer);
                        saveAndRefresh("reward item cost");
                        return;
                    }
                    if (event.getClick().isShiftClick()) {
                        reward.getCost().setMoneyCost(0.0D);
                        editorSounds.delete(viewer);
                        saveAndRefresh("reward money cost");
                        return;
                    }
                    if (viewer.getInventory().getItemInMainHand().getType().isAir()) {
                        promptMoney("Enter the money cost.", String.valueOf(reward.getCost().getMoneyCost()), reward.getCost()::setMoneyCost, "Use a number like 500, 1.5k, or 1Qa.");
                    } else {
                        editorSounds.error(viewer);
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
                editorSounds.cycle(viewer);
                saveAndRefresh("reward stock mode");
            }
            case 24 -> {
                if (event.getClick().isShiftClick()) {
                    plugin.getMarketService().getMarketData().getRewards().remove(reward.getId());
                    plugin.getMarketService().persistRewardRemoval(reward.getId());
                    editorSounds.delete(viewer);
                    plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward deletion"));
                    plugin.getGuiManager().openRewardListSilently(viewer, 0);
                } else {
                    editorSounds.error(viewer);
                }
            }
            case 31 -> {
                editorSounds.back(viewer);
                plugin.getGuiManager().openRewardListSilently(viewer, 0);
            }
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
            editorSounds.addItem(viewer);
            saveAndRefresh("reward icon");
            return;
        }
        if (event.getRawSlots().contains(20)) {
            reward.setRewardItem(dragged);
            editorSounds.addItem(viewer);
            saveAndRefresh("reward item");
            return;
        }
        if ((reward.getCost().getType() == CostType.ITEM || reward.getCost().getType() == CostType.BOTH) && event.getRawSlots().contains(16)) {
            reward.getCost().setItemCost(dragged);
            editorSounds.addItem(viewer);
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
        openEditorInput(editorTextRequest("Enter the new reward id.", reward.getId()), input -> {
            String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_").trim();
            if (id.isBlank()) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Reward ids must contain letters or numbers."));
            } else if (!id.equalsIgnoreCase(reward.getId()) && plugin.getMarketService().getMarketData().getRewards().containsKey(id)) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "That reward id already exists."));
            } else {
                String previousId = reward.getId();
                plugin.getMarketService().getMarketData().getRewards().remove(previousId);
                reward.setId(id);
                plugin.getMarketService().getMarketData().getRewards().put(id, reward);
                plugin.getMarketService().persistRewardChange(previousId, id);
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward id"));
                runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, id));
                return;
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, reward.getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, reward.getId());
        });
    }

    private void promptString(String prompt, String currentValue, java.util.function.Consumer<String> consumer) {
        openEditorInput(editorTextRequest(prompt, currentValue), input -> {
            consumer.accept(input);
            persistCurrentRewardChange();
            editorSounds.save(viewer);
            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId());
        });
    }

    private void promptDouble(String prompt, String currentValue, java.util.function.DoubleConsumer consumer, String invalidReason) {
        openEditorInput(editorNumberRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Double.parseDouble(input.trim()));
                persistCurrentRewardChange();
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId());
        });
    }

    private void promptMoney(String prompt, String currentValue, java.util.function.DoubleConsumer consumer, String invalidReason) {
        openEditorInput(editorMoneyRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(LargeNumberParser.parseDouble(input.trim())
                        .orElseThrow(() -> new NumberFormatException("Invalid money amount")));
                persistCurrentRewardChange();
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId());
        });
    }

    private void promptInt(String prompt, String currentValue, java.util.function.IntConsumer consumer, String invalidReason) {
        openEditorInput(editorNumberRequest(prompt, currentValue), input -> {
            try {
                consumer.accept(Integer.parseInt(input.trim()));
                persistCurrentRewardChange();
                editorSounds.save(viewer);
                plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward value"));
            } catch (NumberFormatException exception) {
                editorSounds.error(viewer);
                plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", invalidReason));
            }
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId());
        });
    }

    private void promptCommands(RewardDefinition reward) {
        openEditorInput(editorCommandRequest(
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
            editorSounds.save(viewer);
            plugin.getMessages().send(viewer, "messages.editor-saved", Map.of("target", "reward commands"));
            runForViewer(() -> plugin.getGuiManager().openRewardEditorSilently(viewer, reward.getId()));
        }, () -> {
            plugin.getMessages().send(viewer, "messages.editor-cancelled");
            plugin.getGuiManager().openRewardEditorSilently(viewer, reward.getId());
        });
    }

    private void assignTopItem(java.util.function.Consumer<ItemStack> consumer, String target, InventoryClickEvent event) {
        if (event.getClick() == ClickType.RIGHT) {
            consumer.accept(null);
            editorSounds.delete(viewer);
            saveAndRefresh(target);
            return;
        }

        ItemStack item = inputItem(event.getCursor());
        if (item == null) {
            editorSounds.addItemError(viewer);
            plugin.getMessages().send(viewer, "messages.editor-invalid", Map.of("reason", "Hold an item in your main hand first."));
            return;
        }
        consumer.accept(item);
        editorSounds.addItem(viewer);
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
        plugin.getGuiManager().openRewardEditorSilently(viewer, getReward().getId());
    }

    private ItemStack createDisplayIconButton(RewardDefinition reward) {
        return createEditorItemButton(Material.CHEST, "#03fc88Display Icon", reward.getIcon(), false);
    }

    private ItemStack createRewardItemButton(RewardDefinition reward) {
        return createEditorItemButton(Material.DIAMOND, "#03fc88Reward Item", reward.getRewardItem(), false);
    }

    private ItemStack createRarityButton(RewardDefinition reward) {
        List<String> rarityIds = new ArrayList<>(plugin.getMarketService().getMarketData().getRarities().keySet());
        List<String> information = rarityIds.isEmpty()
            ? List.of("#ff5d73No rarities configured.")
            : CycleOptions.information(
                plugin.getMessages(),
                reward.getRarityId(),
                rarityIds.stream()
                    .map(id -> new CycleOption(id, id))
                    .toList()
            );
        return button(Material.NETHER_STAR, "#03fc88", "Rarity", information, "cycle the rarity");
    }

    private ItemStack createCostTypeButton(RewardDefinition reward) {
        CostType type = reward.getCost().getType();
        List<String> information = CycleOptions.information(plugin.getMessages(), type.name(), List.of(
            new CycleOption(CostType.MONEY.name(), "Money"),
            new CycleOption(CostType.ITEM.name(), "Item"),
            new CycleOption(CostType.BOTH.name(), "Both")
        ));
        return button(costTypeMaterial(type), "#03fc88", "Cost Type", information, "cycle the cost type");
    }

    private ItemStack createStockModeButton(RewardDefinition reward) {
        StockMode stockMode = reward.getStockMode();
        List<String> information = CycleOptions.information(plugin.getMessages(), stockMode.name(), List.of(
            new CycleOption(StockMode.SERVER_WIDE.name(), StockMode.SERVER_WIDE.getDisplayName()),
            new CycleOption(StockMode.PER_PLAYER.name(), StockMode.PER_PLAYER.getDisplayName())
        ));
        return button(stockMode == StockMode.SERVER_WIDE ? Material.ENDER_CHEST : Material.PLAYER_HEAD,
            "#03fc88", "Stock Mode", information, "cycle the stock mode");
    }

    private ItemStack createCostButton(RewardDefinition reward) {
        if (reward.getCost().getType() == CostType.MONEY) {
            return button(Material.EMERALD, "#03fc88", "Cost Value", List.of(
                "Current: " + plugin.getMarketService().describeCost(reward),
                "Click to edit the money cost."
            ), "edit the money cost");
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
        return button(Material.CRAFTER, "#03fc88", "Money + Item Cost", lore, "edit the item cost");
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
        return button(Material.COMMAND_BLOCK, "#03fc88", "Commands", lore, "edit commands");
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
