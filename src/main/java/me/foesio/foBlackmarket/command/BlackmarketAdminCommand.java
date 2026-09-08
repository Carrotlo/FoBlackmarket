package me.foesio.foBlackmarket.command;

import java.util.Map;
import me.foesio.core.command.FoAdminArguments;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminCommandContext;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.command.FoAdminSubcommand;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.config.ConfigManager;
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.market.MarketService;
import org.bukkit.entity.Player;

public final class BlackmarketAdminCommand {

    private static final String ADMIN_COMMAND = "foblackmarketadmin";
    private static final String ADMIN_PERMISSION = "foblackmarket.admin";

    private BlackmarketAdminCommand() {
    }

    public static void register(
        FoBlackmarket plugin,
        ConfigManager configManager,
        MarketService marketService,
        GuiManager guiManager,
        UpdateNoticeService updateNotices
    ) {
        FoAdminCommand.builder(plugin, plugin.getMessages())
            .commandName(ADMIN_COMMAND)
            .permission(ADMIN_PERMISSION)
            .versionCommand(false)
            .adminSounds(plugin.getAdminSounds())
            .adminMessages(adminMessages())
            .defaultExecutor(ctx -> {
                ctx.messages().send(ctx.sender(), "messages.invalid-subcommand");
                plugin.getAdminSounds().updateError(ctx.sender());
                return true;
            })
            .addSubcommand(versionSubcommand(updateNotices))
            .addSubcommand(editorSubcommand(plugin, guiManager))
            .addSubcommand(reloadSubcommand(plugin))
            .addSubcommand(restockSubcommand(plugin, marketService))
            .addSubcommand(openGuiSubcommand(plugin, configManager, guiManager))
            .register();
    }

    private static FoAdminMessages adminMessages() {
        return FoAdminMessages.builder()
            .generalNoPermission("messages.no-permission", "{prefix} {bad}You do not have permission.")
            .generalPlayerOnly("messages.player-only", "{prefix} {bad}Only players can use this.")
            .usage("messages.invalid-subcommand", "{prefix} {bad}Usage: {theme}/foblackmarketadmin <version|editor|reload|restock|opengui>{muted}.")
            .commandMissing("messages.invalid-subcommand", "{prefix} {bad}Admin command {theme}{command}{bad} is missing from plugin.yml.")
            .commandFailed("messages.command-failed", "{prefix} {bad}Command failed. {muted}{error}")
            .reloadSuccess("messages.reload-success", "{prefix} {good}Config, messages, GUIs and market data reloaded.")
            .reloadFailed("messages.reload-failed", "{prefix} {bad}Reload failed at {theme}{step}{bad}. {muted}{error}")
            .editorOpened("messages.editor-saved", "{prefix} {good}Opened the editor.")
            .build();
    }

    private static FoAdminSubcommand versionSubcommand(UpdateNoticeService updateNotices) {
        return FoAdminSubcommand.builder("version", ctx -> {
            updateNotices.checkAndSendVersion(ctx.sender());
            return true;
        }).usage("version").build();
    }

    private static FoAdminSubcommand editorSubcommand(FoBlackmarket plugin, GuiManager guiManager) {
        return FoAdminSubcommand.builder("editor", ctx -> {
            guiManager.openMainEditor(ctx.playerOrNull());
            return true;
        }).playerOnly().usage("editor").build();
    }

    private static FoAdminSubcommand reloadSubcommand(FoBlackmarket plugin) {
        return FoAdminSubcommand.builder("reload", ctx -> {
            FoReloadResult result = plugin.reloadFiles();
            if (result.successful()) {
                if (ctx.playerOrNull() != null) {
                    plugin.getAdminSounds().reload(ctx.playerOrNull());
                }
                plugin.getMessages().send(ctx.sender(), "messages.reload-success");
            } else {
                if (ctx.playerOrNull() != null) {
                    plugin.getAdminSounds().reloadError(ctx.playerOrNull());
                }
                plugin.getMessages().send(ctx.sender(), "messages.reload-failed", Map.of(
                    "step", result.failedStep(),
                    "error", result.errorMessage()
                ));
            }
            return true;
        }).usage("reload").build();
    }

    private static FoAdminSubcommand restockSubcommand(FoBlackmarket plugin, MarketService marketService) {
        return FoAdminSubcommand.builder("restock", ctx -> {
            marketService.restockNow(true);
            plugin.getMessages().send(ctx.sender(), "messages.restock-success");
            return true;
        }).usage("restock").build();
    }

    private static FoAdminSubcommand openGuiSubcommand(FoBlackmarket plugin, ConfigManager configManager, GuiManager guiManager) {
        return FoAdminSubcommand.builder("opengui", ctx -> openGui(plugin, configManager, guiManager, ctx))
            .usage("opengui <player>")
            .tabCompleter(ctx -> FoAdminArguments.onlinePlayer().complete(ctx.subArg(0)))
            .build();
    }

    private static boolean openGui(FoBlackmarket plugin, ConfigManager configManager, GuiManager guiManager, FoAdminCommandContext ctx) {
        if (ctx.subArgs().length < 1) {
            plugin.getMessages().send(ctx.sender(), "messages.invalid-opengui");
            plugin.getAdminSounds().updateError(ctx.sender());
            return true;
        }

        Player target = FoAdminArguments.onlinePlayer().parse(ctx.subArg(0)).orElse(null);
        if (target == null) {
            plugin.getMessages().send(ctx.sender(), "messages.player-not-found");
            plugin.getAdminSounds().updateError(ctx.sender());
            return true;
        }

        GuiManager.OpenPlayerMarketResult result = guiManager.openPlayerMarket(target);
        if (result == GuiManager.OpenPlayerMarketResult.OPENED) {
            plugin.getMessages().send(ctx.sender(), "messages.opengui-success", Map.of("player", target.getName()));
        } else if (result == GuiManager.OpenPlayerMarketResult.COOLDOWN && configManager.shouldSendCooldownFeedback()) {
            plugin.getMessages().send(ctx.sender(), "messages.opengui-cooldown");
            plugin.getAdminSounds().updateError(ctx.sender());
        }
        return true;
    }
}
