package me.foesio.foBlackmarket.hook;

import me.foesio.foBlackmarket.FoBlackmarket;
import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultHook {

    private final FoBlackmarket plugin;
    private Economy economy;

    public VaultHook(FoBlackmarket plugin) {
        this.plugin = plugin;
        hook();
    }

    public void hook() {
        this.economy = null;
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }

        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            this.economy = provider.getProvider();
        }
    }

    public boolean hasEconomy() {
        return economy != null;
    }

    public boolean has(Player player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (economy == null) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public String format(double amount) {
        if (economy == null) {
            return fallbackFormat(amount);
        }
        try {
            return economy.format(amount);
        } catch (RuntimeException ignored) {
            return fallbackFormat(amount);
        }
    }

    private String fallbackFormat(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }
}
