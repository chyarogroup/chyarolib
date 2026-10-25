# chyarolib

<div align="center">
  <p>
    <img alt="paper" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg">
    <img alt="purpur" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/purpur_vector.svg">
    <img alt="spigot" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/unsupported/spigot_vector.svg">
  </p>

  <p>
    <a href="https://github.com/chyarogroup/chyarolib"><img alt="github" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg"></a>
  </p>

  <p>
    <a href="https://discord.gg/JshjxSMnjv"><img alt="discord-singular" height="56" src="https://raw.githubusercontent.com/MaboroshiKobo/branding/refs/heads/main/socials/128x/discord_icon_bg.png"></a>
  </p>
</div>

chyarolib is the shared runtime behind GenSprout and its addons. It loads addon jars, hot-reloads plugins and addons without restarting the server, and provides SQLite databases, managed worlds and a few server utilities. GenSprout runs without it; install chyarolib when you want addons such as GenSprout SkyBlock or GenSprout Mine.

## Key Features

### Addon Runtime
* **Drop-in addons**: Addons are plain jars in the host plugin's addon folder, for example `plugins/GenSprout/addons/`. They do not appear in the server's plugin list.
* **Safe loading**: Each addon declares its host, the host versions it supports and the chyarolib version it needs. Addons that don't fit are skipped with a clear message instead of breaking the server.
* **Dependencies**: Addons can depend on each other and are loaded in the right order.
* **Isolation**: Every addon gets its own class loader, data folder, commands, listeners and tasks. They are cleaned up completely when it unloads.
* **Shared API**: Addons talk to GenSprout through a stable bridge covering economy, progression, generators, harvests, teams and the guide, without touching GenSprout's internals.

### Managed Hot Reloads
* `/chyarolib reload <host>` swaps in a new plugin jar while the server keeps running. Data is saved first and open menus are closed.
* `/chyarolib reload <host>addon [addon-id]` reloads one addon or all of them.
* Previous jars are cached (`cache-versions`), and managed worlds are briefly paused with a message while their addon reloads.

### Services for Plugins and Addons
* **SQLite databases**: One managed connection per component, with versioned migrations that run in a transaction and roll back cleanly on failure.
* **Managed worlds**: Void worlds for addons, such as SkyBlock islands, owned and protected while the addon reloads.
* **Config migration**: New default settings are merged into existing config files without overwriting what you changed.

### Server Utilities
* `/restart`: a 30-second restart with an action-bar countdown and a title countdown for the last 10 seconds. Use `/restart now` to skip it.
* `/updatejar`: downloads the latest Purpur build for your Minecraft version. It can also run on startup (`auto-download-on-startup`).
* `/updatecheck`: checks Modrinth for a newer GenSprout release. It also runs on startup.
* Commands support target selectors (`@p`, `@a`, `@r`, `@s`) and `/execute as`.

## Commands & Permissions

| Command | Permission | What it does |
| --- | --- | --- |
| `/chyarolib reload <host\|hostaddon> [addon-id]` | `chyarolib.reload` | Hot-reload a plugin or its addons |
| `/restart [now]` | `chyarolib.admin` | Restart with a countdown |
| `/updatejar` | `chyarolib.admin` | Download the latest Purpur jar |
| `/updatecheck` | `chyarolib.admin` | Check for GenSprout updates |

`chyarolib.admin` includes `chyarolib.reload`. Both are given to ops by default.

## Requirements
* Paper or Purpur 1.21.7 or newer (tested on 26.2).
* Java 21 or newer.
* For addons: GenSprout 1.3.0-beta1 or newer.

## Installation
1. Put `chyarolib.jar` in `plugins/`.
2. Put addon jars in the host plugin's addon folder, for example `plugins/GenSprout/addons/`.
3. Restart the server. After that, use `/chyarolib reload` to update GenSprout or an addon without restarting.
