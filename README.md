<div align="center">

# CloverChat

**A configurable chat layer for Minecraft servers — local and global chat, private messages, channels, moderation tools and cross-server sync.**

[![Build](https://github.com/slyphmp4/CloverChat/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/slyphmp4/CloverChat/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/slyphmp4/CloverChat?style=flat-square)](https://github.com/slyphmp4/CloverChat/releases)
[![API](https://img.shields.io/badge/API-1.16%20%E2%86%92%2026.2-555?style=flat-square)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-555?style=flat-square)](https://papermc.io/software/folia)
[![Author](https://img.shields.io/badge/author-slyph-555?style=flat-square)](https://github.com/slyphmp4)

[Releases](https://github.com/slyphmp4/CloverChat/releases) · [Builds](https://github.com/slyphmp4/CloverChat/actions) · [Issues](https://github.com/slyphmp4/CloverChat/issues)

</div>

---

## Overview

CloverChat replaces the usual pile of small chat plugins with one coherent chat pipeline. It handles message routing, formatting, private messages, moderation metadata and optional proxy synchronization while keeping most behavior configurable from YAML.

It is intentionally modular: features can be disabled independently, and integrations such as PlaceholderAPI and CloverBadges are optional.

### At a glance

| Area | What CloverChat handles |
| --- | --- |
| Chat routing | Local chat, global chat and permission-based group channels |
| Private messages | `/m`, cooldowns, notification sounds and quick right-click PMs |
| Formatting | HEX colors, permission/group styles, gradients and text decorations |
| Interaction | Mentions, clickable links, prefix/message hovers and message IDs |
| Moderation | Persistent message inspector with SQLite or MySQL storage |
| Immersion | Configurable messages above player heads |
| Network | Signed Velocity plugin-message synchronization for global chat |
| Server messages | Join/leave messages, censorship and automatic announcements |
| Localization | Russian, English, Ukrainian and German language files |

---

## Compatibility

CloverChat keeps `api-version: 1.16` and its normal bytecode target at Java 11 for broad server compatibility. The project itself is built with a Java 25 toolchain, and CI also compiles the same sources against Paper 26.2.

| Component | Status |
| --- | --- |
| Paper / compatible Bukkit implementations | Supported |
| Paper 1.16.5 API | Main compile target |
| Paper 26.2 API | Verified by the build |
| Folia | Declared supported |
| PlaceholderAPI | Optional |
| CloverBadges | Optional |
| Velocity | Optional, for global-chat sync |

Use the Java version required by your Minecraft server. Modern 26.2 servers require their modern Java runtime even though CloverChat's own main bytecode target remains Java 11.

---

## Installation

1. Download the latest CloverChat JAR from [Releases](https://github.com/slyphmp4/CloverChat/releases) or build it from source.
2. Put `CloverChat.jar` into the server's `plugins/` directory.
3. Start the server once so CloverChat can create its configuration and language files.
4. Edit `plugins/CloverChat/config.yml` and the files you need.
5. Apply configuration changes with:

```text
/cloverchatreload confirm
```

For a normal plugin update, a full server restart is preferable to Bukkit's global `/reload`.

---

## Chat model

CloverChat separates chat into three useful layers instead of treating every message the same way.

### Local chat

Local chat is enabled by default and reaches players within the configured radius.

```yaml
local-chat:
  enabled: true
  radius: 70
```

### Global chat

A configurable prefix switches a message into global chat. The default is `!`.

```yaml
global-chat:
  enabled: true
  prefix: "!"
```

So `hello` stays local, while `!hello` is global.

### Group channels

Private staff or role channels are configured as named channels with their own prefix and send/view permissions.

```yaml
group-chat:
  enabled: true
  channels:
    admin:
      enabled: true
      prefix: "@a "
      display-name: "Admin"
      send-permission: "cloverchat.channel.admin.send"
      view-permission: "cloverchat.channel.admin.view"
```

The default configuration also includes examples for moderator, VIP and builder channels. When prefixes overlap, CloverChat uses the longest matching prefix.

---

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/m <player> <message>` | Send a private message | `cloverchat.pm` |
| `/cloverchat inspect <message_id>` | Inspect a stored chat message | `cloverchat.command.inspect` |
| `/cloverchatreload confirm` | Reload CloverChat configuration | `cloverchat.command.reload` |

Tab completion is permission-aware, so administrative suggestions are not exposed to players who cannot use them.

### Core permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `cloverchat.chat.use` | Everyone | Send chat messages |
| `cloverchat.pm` | Everyone | Use private messages |
| `cloverchat.pm.quick` | Everyone | Use quick right-click PMs |
| `cloverchat.pm.bypass.cooldown` | OP | Bypass PM cooldown |
| `cloverchat.command.inspect` | OP | Inspect stored messages |
| `cloverchat.command.inspect.all` | OP | Inspect restricted group-channel messages |
| `cloverchat.command.reload` | OP | Reload CloverChat |
| `cloverchat.commandcooldown.bypass` | OP | Bypass the general command cooldown |

Channel permissions such as `cloverchat.channel.admin.send` and `cloverchat.channel.admin.view` are defined by the channel configuration itself.

---

## Message styles

CloverChat can style only the message body without recoloring the player's prefix, nickname, reputation or badges.

Rules are evaluated by priority and can select players by:

- a permission node;
- an UltraPermissions group through `%uperms_group%` and PlaceholderAPI.

Styles support solid colors, multi-stop gradients, bold, italic, underline and strikethrough.

```yaml
message-styles:
  enabled: true
  rules:
    vip:
      enabled: true
      priority: 100
      selector:
        type: "PERMISSION"
        value: "cloverchat.message-style.vip"
      style:
        color: "&#FFD667"
        gradient:
          enabled: true
          colors:
            - "&#FFD667"
            - "&#FFF3D6"
        bold: false
```

Accepted color forms include `&c`, `#FF5555`, `&#FF5555` and `&FF5555`.

When CloverBadges is installed, CloverChat can also consume the selected CloverBadges message-color style instead of duplicating that cosmetic state inside the chat plugin.

---

## Private messages and interaction

Private chat supports more than the `/m` command:

- configurable cooldowns;
- an incoming-message sound;
- quick PMs by right-clicking another player;
- optional sneak requirement for quick PMs;
- clickable command suggestions;
- `@player` mention sounds;
- clickable links when link handling is enabled.

The chat hover layer can also expose message metadata and copy a message ID for later inspection.

---

## Message Inspector

Every message can receive a stable message ID and be written to the moderation store. Staff can then retrieve the original record with:

```text
/cloverchat inspect <message_id>
```

An inspected record can include the author, UUID, group, prefix, reputation, timestamp, server, world coordinates, chat mode, channel, raw text and final rendered text.

Two storage modes are available:

| Mode | Backend |
| --- | --- |
| `LOCAL` | SQLite file inside the CloverChat data folder |
| `REMOTE` | MySQL through HikariCP |

The inspector has configurable retention, batched writes, recent-ID caching, health checks and JSONL backups. Restricted group-channel records still respect their view permission unless the moderator has `cloverchat.command.inspect.all`.

---

## Velocity sync

CloverChat can synchronize **global chat only** between backend servers through Velocity plugin messages. Local chat, group channels and private messages remain on their originating backend.

On Velocity, enable:

```toml
bungee-plugin-message-channel = true
```

Then enable `proxy-sync` on every backend, give each server a unique `server-id`, and use the same subchannel and shared secret everywhere.

The shared secret must be at least 32 characters. Prefer the `CLOVERCHAT_PROXY_SECRET` environment variable instead of committing it to `config.yml`.

Incoming messages are protected by message-age checks and a deduplication cache.

---

## Configuration files

CloverChat keeps runtime configuration split by responsibility:

```text
plugins/CloverChat/
├── config.yml
├── auto-messages.yml
├── langs/
│   ├── de/
│   │   ├── hovers.yml
│   │   └── messages.yml
│   ├── en/
│   │   ├── hovers.yml
│   │   └── messages.yml
│   ├── ru/
│   │   ├── hovers.yml
│   │   └── messages.yml
│   └── ua/
│       ├── hovers.yml
│       └── messages.yml
└── storage/
    └── message-inspector.db    # when LOCAL inspector storage is used
```

`config.yml` controls routing, styles, channels, PMs, mentions, overhead chat, hovers, links, cooldowns, join/leave messages, censorship, proxy sync and inspector storage.

`auto-messages.yml` contains automatic announcements, while each language directory keeps normal messages and hover text separate.

---

## Integrations

### PlaceholderAPI

PlaceholderAPI is optional but recommended when the chat format should consume values from other plugins. The default configuration already includes examples such as:

```text
%player_name%
%cloverrep_nick%
%cloverrep_reputation%
```

### CloverBadges

CloverBadges is a soft dependency. CloverChat contains a dedicated bridge for CloverBadges message colors, allowing a player's selected chat gradient/style to be rendered by the chat pipeline.

### UltraPermissions

UltraPermissions is not a hard dependency. Group-based message styles work through PlaceholderAPI and `%uperms_group%`.

---

## Building from source

CloverChat uses the Gradle Wrapper and Shadow.

```bash
git clone https://github.com/slyphmp4/CloverChat.git
cd CloverChat
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

The shaded plugin is written to `build/libs/CloverChat.jar`.

The normal build compiles the plugin for its broad compatibility target, while `check` additionally compiles the source set against Paper 26.2.

---

## Related Clover plugins

- [CloverBadges](https://github.com/slyphmp4/CloverBadges) — badges, nickname colors and message-color cosmetics.
- [CloverRep](https://github.com/slyphmp4/CloverRep) — player reputation and reputation-aware nickname formatting.
- [CloverReports](https://github.com/slyphmp4/CloverReports) — report and moderation workflow.

CloverChat is maintained by **slyph**.
