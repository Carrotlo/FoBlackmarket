package me.foesio.foBlackmarket.command;

import java.util.Map;
import me.foesio.foBlackmarket.FoBlackmarket;
import me.foesio.foBlackmarket.config.ConfigManager;
import me.foesio.foBlackmarket.gui.GuiManager;
import me.foesio.foBlackmarket.market.MarketService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BlackmarketCommand implements CommandExecutor {

    private final FoBlackmarket plugin;
    private final ConfigManager configManager;
    private final MarketService marketService;
    private final GuiManager guiManager;

    public BlackmarketCommand(FoBlackmarket plugin, ConfigManager configManager, MarketService marketService, GuiManager guiManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.marketService = marketService;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("foblackmarket.use")) {
            plugin.getMessages().send(sender, "messages.no-permission", "");
            plugin.getAdminSounds().updateError(sender);
            return true;
        }
        if (!marketService.hasActiveMarket()) {
            plugin.getMessages().send(sender, "messages.market-unavailable", "");
            plugin.getAdminSounds().updateError(sender);
            return true;
        }

        if (configManager.shouldCommandOpenGui()) {
            if (!(sender instanceof Player player)) {
                plugin.getMessages().send(sender, "messages.player-only", "");
                return true;
            }

            GuiManager.OpenPlayerMarketResult result = guiManager.openCommandMarket(player);
            if (result == GuiManager.OpenPlayerMarketResult.UNAVAILABLE) {
                plugin.getMessages().send(sender, "messages.market-unavailable", "");
            }
            if (result == GuiManager.OpenPlayerMarketResult.COOLDOWN && configManager.shouldSendCooldownFeedback()) {
                plugin.getMessages().send(sender, "messages.market-cooldown", "");
                plugin.getAdminSounds().updateError(sender);
            }
            return true;
        }

        plugin.getMessages().send(sender, "messages.blackmarket-info", "", Map.of(
            "time_remaining", configManager.formatDuration(marketService.getTimeUntilRestock())
        ));
        return true;
    }
}
