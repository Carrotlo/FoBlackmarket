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

This plugin does not expose a public developer API.

</details>
