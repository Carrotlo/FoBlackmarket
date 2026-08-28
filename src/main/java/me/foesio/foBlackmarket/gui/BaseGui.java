package me.foesio.foBlackmarket.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.CursorItemEditor;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.text.FoText;
import me.foesio.foBlackmarket.FoBlackmarket;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public abstract class BaseGui implements InventoryHolder {

    protected final FoBlackmarket plugin;
    protected final Player viewer;
    protected final Inventory inventory;
    protected final GuiButtonConfig buttons;
    protected final FoEditorSounds editorSounds;

    protected BaseGui(FoBlackmarket plugin, Player viewer, int size, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.buttons = GuiButtonConfig.defaults();
        this.editorSounds = plugin.getEditorSounds();
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public void open() {
        open(true);
    }

    public void openSilently() {
        open(false);
    }

    private void open(boolean playOpenSound) {
        refresh();
        viewer.openInventory(inventory);
        if (playOpenSound) {
            openSound();
        }
    }

    protected void openSound() {
        editorSounds.open(viewer);
    }

    public void refresh() {
        ItemStack[] previousContents = inventory.getContents().clone();
        inventory.clear();
        render();
        if (hasInventoryChanged(previousContents, inventory.getContents())
            && viewer.getOpenInventory().getTopInventory().equals(inventory)) {
            viewer.updateInventory();
        }
    }

    protected abstract void render();

    public void handleClick(InventoryClickEvent event) {
    }

    public void handleClose(InventoryCloseEvent event) {
    }

    public void handleDrag(InventoryDragEvent event) {
    }

    public boolean allowsPlayerInventoryInteraction() {
        return false;
    }

    protected TextDialogRequest editorTextRequest(String prompt, String currentValue) {
        return keepInventoryReopenOnCancel(TextDialogRequest.text(prompt, currentValue));
    }

    protected TextDialogRequest editorNumberRequest(String prompt, String currentValue) {
        return keepInventoryReopenOnCancel(TextDialogRequest.number(prompt, currentValue));
    }

    protected TextDialogRequest editorCommandRequest(String prompt, String currentValue, String placeholder) {
        return keepInventoryReopenOnCancel(TextDialogRequest.command(List.of(prompt), currentValue, placeholder));
    }

    protected TextDialogRequest editorMoneyRequest(String prompt, String currentValue) {
        return new TextDialogRequest(
            "Money Input",
            List.of(prompt, "Enter a money amount, then confirm."),
            "Money",
            currentValue,
            "500 or 500.0",
            DialogButton.save(),
            DialogButton.cancel(),
            320,
            300,
            64,
            true,
            false,
            false
        );
    }

    protected boolean openEditorInput(
            TextDialogRequest request,
            Consumer<String> onSubmit,
            Runnable onCancel
    ) {
        boolean openedNative = EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                viewer,
                request,
                onSubmit,
                () -> {
                    editorSounds.back(viewer);
                    onCancel.run();
                }
        );
        if (openedNative) {
            editorSounds.open(viewer);
        }
        return openedNative;
    }

    private TextDialogRequest keepInventoryReopenOnCancel(TextDialogRequest request) {
        return new TextDialogRequest(
            request.title(),
            request.body(),
            request.fieldLabel(),
            request.initialValue(),
            request.placeholder(),
            request.submitButton(),
            request.cancelButton(),
            request.bodyWidth(),
            request.inputWidth(),
            request.maxLength(),
            request.labelVisible(),
            false,
            request.pause()
        );
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void fill(ItemStack filler) {
        if (filler == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler.clone());
            }
        }
    }

    protected void fillEditorBackground() {
        fill(EditorItemFactory.filler());
    }

    protected int bottomMiddleSlot(int rows) {
        return GuiSlots.bottomMiddleSlot(rows);
    }

    protected ItemStack inputItem(ItemStack cursor) {
        return CursorItemEditor.cloneInputItem(viewer, cursor).orElse(null);
    }

    protected void runForViewer(Runnable action) {
        plugin.getCore().scheduler().runForPlayer(viewer, action);
    }

    protected ItemStack item(Material material, String name, String... lore) {
        return item(material, name, Arrays.asList(lore));
    }

    protected ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FoText.color(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(FoText.color(line));
            }
            meta.setLore(lines);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    protected boolean itemsEqual(ItemStack first, ItemStack second) {
        if (first == null || first.getType().isAir()) {
            return second == null || second.getType().isAir();
        }
        if (second == null || second.getType().isAir()) {
            return false;
        }
        return first.equals(second);
    }

    private boolean hasInventoryChanged(ItemStack[] previousContents, ItemStack[] currentContents) {
        if (previousContents.length != currentContents.length) {
            return true;
        }
        for (int slot = 0; slot < previousContents.length; slot++) {
            if (!itemsEqual(previousContents[slot], currentContents[slot])) {
                return true;
            }
        }
        return false;
    }
}
