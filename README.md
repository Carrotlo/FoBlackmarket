# FoBlackmarket

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/foblackmarket) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/foblackmarket/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

A timed, stock-aware black market with editable item and command offers.

*Developed by Carrotio.*

## Features

- Runs a timed black market with configurable restocks and availability windows.
- Supports item offers and command rewards with stock limits.
- Accepts configurable costs, including Vault economy pricing when available.
- Provides separate player and command-market interfaces.
- Includes an admin editor and a time-remaining placeholder.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/foblackmarket). Check the [versions page](https://modrinth.com/plugin/foblackmarket/versions) for supported Minecraft versions and server platforms.

- **Required:** None
- **Optional integrations:** `PlaceholderAPI`, `Vault`

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/blackmarket` — Shows black market information Aliases: `/bm`, `/foblackmarket`.
- `/foblackmarketadmin` — Admin command for FoBlackmarket Aliases: `/blackmarketadmin`, `/bmadmin`.

### Permissions

- `foblackmarket.use` — Allows players to use /blackmarket Default: `true`.
- `foblackmarket.admin` — Allows access to FoBlackmarket admin tools Default: `op`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

Requires PlaceholderAPI.

- `%foblackmarket_time_remaining%` — Formatted time until the next market restock.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `market.yml` — Market offers, stock, costs, and rewards.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.
- `guis/*.yml` — GUI layouts, slots, labels, and lore.

Public number locale and complete time-unit phrases are in `messages.yml` under `gui.number-locale` and `gui.duration-*`. Older custom `settings.time-format` unit labels are carried into those phrases during the versioned upgrade.

Main config areas: `settings`, `native-dialogs`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

This checkout provides a Java API in `me.foesio.foBlackmarket.api`. It is a plugin-owned boundary: consumers must not call `FoBlackmarket#getMarketService()` or use the mutable `market` package as an integration contract.

### Dependency and discovery

Compile against the final `FoBlackmarket-2.5.jar` as a provided or compile-only dependency; do not shade it into your plugin. This checkout has Maven coordinates `me.foesio:FoBlackmarket:2.5`, but no public Maven repository is promised. You can install the built jar into your local Maven repository with `mvn install:install-file -Dfile=target/FoBlackmarket-2.5.jar -DgroupId=me.foesio -DartifactId=FoBlackmarket -Dversion=2.5 -Dpackaging=jar`, then use:

```xml
<dependency>
  <groupId>me.foesio</groupId>
  <artifactId>FoBlackmarket</artifactId>
  <version>2.5</version>
  <scope>provided</scope>
</dependency>
```

An optional `plugin.yml` consumer uses `softdepend: [FoBlackmarket]`; a required consumer uses `depend: [FoBlackmarket]`. Keep direct API references in a separate integration class, loaded only when FoBlackmarket is enabled:

```java
// In your plugin's onEnable():
var provider = getServer().getPluginManager().getPlugin("FoBlackmarket");
if (provider != null && provider.isEnabled()) {
    BlackmarketIntegration.start(this);
}
```

```java
// BlackmarketIntegration.java, loaded only after the provider check above:
import me.foesio.foBlackmarket.api.BlackmarketApi;
import me.foesio.foBlackmarket.api.event.BlackmarketPurchaseCompletedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlackmarketIntegration implements Listener {
    public static void start(JavaPlugin consumer) {
        RegisteredServiceProvider<BlackmarketApi> registration =
            Bukkit.getServicesManager().getRegistration(BlackmarketApi.class);
        if (registration == null) return;
        Bukkit.getPluginManager().registerEvents(
            new BlackmarketIntegration(registration.getProvider()), consumer);
        // For trusted automation: registration.getProvider().requestRestock();
    }

    private final BlackmarketApi api;

    private BlackmarketIntegration(BlackmarketApi api) {
        this.api = api;
    }

    @EventHandler
    public void onPurchase(BlackmarketPurchaseCompletedEvent event) {
        // Award quest progress for event.getPlayer() and event.getOfferId().
        api.currentMarket(event.getPlayer().getUniqueId())
            .ifPresent(market -> { /* Refresh your external display from market.offers(). */ });
    }
}
```

Reacquire the service after a plugin disable/enable. It is unregistered during FoBlackmarket shutdown. A consumer must handle an absent service or an unavailable market; no hot reload across a provider disable is promised.

### Query and action semantics

- `currentMarket(UUID)` returns an immutable copy of the **active player buying slots**, including each specified player's remaining stock. It does not list rewards that exist only in the command market preview. It returns empty when the market is unavailable. Times are Unix epoch milliseconds; cost text is display text, not a price guarantee.
- `quotePurchase(Player, String)` returns an eligibility result for a currently displayed buying offer. It checks stock, reward validity, inventory space, Vault balance, and item cost. It does not reserve stock, check GUI click cooldown or command permission, or change anything. Recheck by making the actual purchase through FoBlackmarket's GUI; there is no public purchase action API.
- `requestRestock()` uses the admin-style broadcast/sound behavior. It returns `RESTOCKED`, `UNAVAILABLE`, or `FAILED` and emits a restock event only after the new state is saved. A failed state save leaves the prior active market intact. A nested restock request while a restock event is being handled returns `FAILED`.
- `openPlayerMarket(Player)` opens the player buying GUI, following its normal availability and cooldown checks. It returns `PLAYER_OFFLINE`, `UNAVAILABLE`, `COOLDOWN`, `ALREADY_OPEN`, or `OPENED`. It is a trusted plugin action like the admin `opengui` command; it does not check the target's `foblackmarket.use` permission.
- All API calls must run on the Bukkit primary server thread. Off-thread calls throw `IllegalStateException`. No Folia/region-thread guarantee is made by this version.

### Events

- `BlackmarketPurchaseRequestEvent` is synchronous and cancellable. It fires only after an initial successful quote, before any payment, item removal, reward, or stock write. Cancellation leaves those unchanged. The quote is immutable and may become stale; purchase requirements are checked again after listeners return. Cancelled attempts do not produce a completion event or default failure feedback.
- `BlackmarketPurchaseCompletedEvent` is synchronous and non-cancellable. It fires once for a successful purchase after payment, item delivery, and stock persistence, before configured reward commands and the market GUI refresh. It carries the player, offer ID, and remaining stock for that sale. It does not fire for denials, cancellations, or a failed stock save. Reward commands are attempted afterward; a command returning `false` does not undo the sale.
- `BlackmarketRestockedEvent` is synchronous and non-cancellable. It fires after a runtime restock is persisted, before GUI refresh and announcements. Causes are `SCHEDULED`, `ADMIN`, `EDITOR`, `API`, and `DIRECT`. Restoring or rebuilding state on startup/reload, and merely rescheduling the timer, do not fire it.

Event handlers run in the caller's Bukkit server-thread context. This plugin does not promise support for calls from region threads or asynchronous workers. Event handlers should read the API afresh when they need the latest state. Do not mutate FoBlackmarket's internal market classes.

### Complete public API method index

Signatures use short type names; import Fo types from this plugin's `api` package and Bukkit types from Bukkit.

#### `BlackmarketApi`

```java
Optional<MarketView> currentMarket(UUID);
PurchaseQuote quotePurchase(Player, String);
RestockResult requestRestock();
OpenMarketResult openPlayerMarket(Player);
```

**Other public contract types:** `MarketView`, `OfferView`, `OpenMarketResult`, `PurchaseQuote`, `RestockCause`, `RestockResult`.

### Complete Bukkit event index

- `BlackmarketPurchaseCompletedEvent`.
- `BlackmarketPurchaseRequestEvent`.
- `BlackmarketRestockedEvent`.

</details>
