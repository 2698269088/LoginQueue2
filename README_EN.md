# LoginQueue2

> **Note: The plugin was originally named LoginSequence2, but it has now been renamed to LoginQueue2.**

A next-generation Minecraft login queue solution, providing high-performance player queuing, load balancing, and cross-server transfer capabilities for network servers. Supports **Spigot, Paper, Folia, Limbo, BungeeCord, and Velocity** platform combinations to meet the deployment needs of various server scales.

---

## Core Features

- **Multi-Platform Support**: The main plugin is compatible with Spigot, Paper, and Folia; companion plugins cover Limbo, BungeeCord, and Velocity platforms
- **Login Queue Management**: Controls the order in which players enter main servers, with priority sorting based on permissions, names, UUIDs, regex patterns, and permission groups
- **Load Balancing**: Automatically selects the optimal server in multi-main server environments (Least Players / Least Load / Round Robin / Random)
- **Dual Work Modes**:
  - **PROXY Mode**: Traditional network server architecture, redirects through a proxy
  - **WORLD Mode**: Creates an independent login world within a single server, no proxy required
- **Player Restrictions**: Restricts movement, interaction, and block breaking for queued players, with optional activity range limits
- **Dimension & Spawn Protection**: Prevents entry into Nether/End, disables portals, and protects the spawn area
- **Performance-Saving Mode**: Disables mob spawning, time flow, and weather changes
- **UDP Status Sync**: Retrieves subserver status directly via UDP without requiring a proxy plugin
- **Built-in Authentication**: Optional register/login/password change functionality, with AuthMe compatibility mode
- **Beacon Queuing**: In manual queue mode, players click a beacon item to join the queue
- **Server Status Scoreboard**: Real-time display of online status for all main servers

---

## Important Notice

### One Plugin Handles Almost Everything

Since **version 1.3**, **a single `LoginQueue2` (or `LoginQueue2Limbo`) main plugin can handle 99% of the functionality**, allowing for a nearly perfect login queue experience without needing the Online/BC/VC companion plugins.

- **Single Server Mode**: Just install the `LoginQueue2` main plugin on your login server (Lobby). That's it.
- **Network Mode (UDP Priority - Recommended)**: Install `LoginQueue2` on the login server and `LoginQueue2Online` on each main server for status reporting. No BC/VC proxy plugins required.
- **The Only Exception**: Remote main server **TPS (ticks per second)** cannot be obtained by a single server alone. It requires the `LoginQueue2Online` plugin (via UDP) or a proxy plugin (BC/VC) to provide complete server load data. If you don't need precise TPS monitoring, the main plugin alone is fully sufficient.

> **Note**: Unless new features are required, the BC/VC and Online companion plugins typically do not need to be updated. These plugins are functionally stable and only need to remain compatible with the main plugin version.

---

## Project Structure

```
LoginQueue2/
├── pom.xml                          # Root aggregator POM
├── LoginQueue2/                     # Main plugin (Spigot/Paper/Folia)
│   └── src/main/java/top/mcocet/loginqueue2/
├── LoginQueue2Limbo/                # Limbo lightweight login server companion
│   └── src/main/java/top/mcocet/loginqueue2limbo/
├── LoginQueue2Online/               # Subserver status reporting plugin (UDP)
│   └── src/main/java/top/mcocet/loginqueue2online/
├── LoginQueue2BC/                   # BungeeCord proxy companion (optional)
│   └── src/main/java/top/mcocet/loginqueue2bc/
└── LoginQueue2VC/                   # Velocity proxy companion (optional)
    └── src/main/java/top/mcocet/loginqueue2vc/
```

## Module Overview

| Module | Platform | Purpose | Required? |
|--------|----------|---------|-----------|
| **LoginQueue2** | Spigot / Paper / Folia 1.13+ | Main plugin providing login queue, player restrictions, commands, load balancing, and all core features | **Yes** |
| **LoginQueue2Limbo** | Limbo | Lightweight login server plugin with identical features to the main plugin, adapted for Limbo | As needed |
| **LoginQueue2Online** | Spigot / Paper / Folia 1.14+ | Subserver plugin reporting online status and TPS via UDP | Recommended (for multi-main) |
| **LoginQueue2BC** | BungeeCord | Proxy plugin handling cross-server transfers and server info queries (BC channel mode) | Optional |
| **LoginQueue2VC** | Velocity 3.x | Velocity proxy plugin with same functionality as LS2BC | Optional |

---

## Build

```bash
mvn clean package
```

After building, all JAR files are automatically copied to the `dist/` folder at the project root:

```
dist/
├── LoginQueue2-x.x.x.jar
├── LoginQueue2BC-x.x.x.jar
├── LoginQueue2Online-x.x.x.jar
├── LoginQueue2VC-x.x.x.jar
└── LoginQueue2Limbo-x.x.x.jar
```

---

## Installation Guide

### Single Server Mode (Simplest)

Only **1 plugin** needed:

1. Place `LoginQueue2-x.x.x.jar` into the `plugins/` folder of your login server (Lobby).
2. Done. No other plugins required — queue, restrictions, and transfers all work out of the box.

### Network Mode (UDP Priority - Recommended)

Only **2 plugins** needed, **no BC/VC proxy plugins required**:

1. **Login Server (Lobby)**: Place `LoginQueue2-x.x.x.jar` (Spigot/Paper/Folia) or `LoginQueue2Limbo-x.x.x.jar` (Limbo)
2. **Main Servers (Main1, Main2...)**: Place `LoginQueue2Online-x.x.x.jar`

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: UDP
  servers:
    - name: "main1"
      host: "127.0.0.1"
      port: 16647
      secret-key: ""
    - name: "main2"
      host: "127.0.0.1"
      port: 16648
      secret-key: ""
```

> **Why UDP priority?** UDP allows direct communication between the main plugin and each subserver, completely bypassing the proxy. This is simpler to deploy and offers better performance. The only extra requirement is installing `LoginQueue2Online` on each main server to report TPS and other status data.

### Network Mode (BungeeCord Native Channel - Optional)

If you prefer not to use UDP, you can use BungeeCord native channels for player transfers. You still only need:

1. **Login Server (Lobby)**: `LoginQueue2-x.x.x.jar` or `LoginQueue2Limbo-x.x.x.jar`
2. **Main Servers (Main)**: `LoginQueue2Online-x.x.x.jar` (for TPS reporting)

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

> Note: When using `BC_CHANNEL` priority, you only need `LoginQueue2BC` or `LoginQueue2VC` if you require proxy-side custom channel transfers. If you only use the native BungeeCord `Connect` channel, you still don't need a proxy plugin.

### Network Mode (Full BC/VC Channel Mode - Optional)

If you need additional features provided by the proxy plugins (such as IP limits, database logging, etc.):

1. **Login Server (Lobby)**: `LoginQueue2-x.x.x.jar` or `LoginQueue2Limbo-x.x.x.jar`
2. **BungeeCord/Velocity Proxy**: `LoginQueue2BC-x.x.x.jar` or `LoginQueue2VC-x.x.x.jar`
3. **Main Servers (Main)**: `LoginQueue2Online-x.x.x.jar`

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### Limbo Lightweight Login Server Mode

Place `LoginQueue2Limbo-x.x.x.jar` into the `plugins/` folder of your Limbo server.

**Limbo Mode Features**:
- Uses Limbo as a lightweight login server with minimal resource usage
- Supports UDP status sync and BungeeCord/Velocity native channels
- Unified beacon click and `/join` command logic
- Identical feature set to the main plugin, adapted for the Limbo API

### Single Server Login World Mode (WORLD Mode)

No proxy needed; creates an independent login world within the same server:

1. Install the [Worlds](https://github.com/TheNextLvl-net/worlds) plugin
2. Place `LoginQueue2-x.x.x.jar` into the `plugins/` folder
3. Edit `config.yml`:
```yaml
work-mode: WORLD
queue:
  spawn:
    world: "login"  # Login world name, must match the world created by Worlds plugin
```

**WORLD Mode Features**:
- No BungeeCord/Velocity proxy required, suitable for single-server architectures
- Players queue in the login world and are teleported to the main world when allowed
- The login world and main world are completely isolated
- Supports all queue management and player restriction features

---

## Configuration

### LoginQueue2 Main Plugin

Edit `plugins/LoginQueue2/config.yml`:

```yaml
# Plugin language (supports zh_CN, zh_TW, en_US)
language: en_US

# Work mode: PROXY (proxy redirect) or WORLD (login world within single server)
work-mode: PROXY

# Enable BungeeCord channel extension (for cross-server transfers, invalid in WORLD mode)
enable-bungee-extension: true

# Enable debug logging (outputs detailed plugin runtime information)
debug: false

# Database configuration (supports sqlite and mysql)
database:
  type: sqlite

# MySQL configuration (only effective when database.type is mysql)
mysql:
  host: localhost
  port: 3306
  database: loginqueue2
  username: root
  password: ""
  table-prefix: lq2_
  use-ssl: false
  auto-reconnect: true

# Authentication system configuration
auth:
  enabled: false
  priority: AUTHME
  authme-compat-mode: false
  min-password-length: 4
  max-password-length: 32
  register-cooldown: 5
  login-cooldown: 1
  auto-queue-after-login: false
  allowed-commands: []

# UDP server status synchronization
udp-sync:
  enabled: false
  priority: BC_CHANNEL
  timeout: 3000
  planned-key: "loginqueue2"
  servers:
    - name: "main1"
      host: "127.0.0.1"
      port: 16647
      game-port:
      secret-key: ""
      max-online: 50
    - name: "main2"
      host: "127.0.0.1"
      port: 16648
      game-port:
      secret-key: ""
      max-online: 50

# Login queue configuration
queue:
  main-server: "main"
  max-online: 50
  balance-strategy: LEAST_PLAYERS
  priority-enabled: true
  priority:
    - "permission:loginqueue2.vip:100"
    - "permission:loginqueue2.priority:80"
    - "regex:^[A-Z].*:50"
  default-priority: 0
  refresh-interval: 5
  offline-refresh-interval: 10
  threshold: 0.8
  restrict-movement: false
  performance-mode: true
  auto-queue: false
  lock-time: 3
  set-gamemode: true
  gamemode: ADVENTURE
  allow-block-interact: false
  allow-block-place: false
  allow-block-break: false
  disable-nether: true
  disable-end: true
  disable-portals: true
  admin-bypass: true
  restrict-range: false
  range-limit: 10
  spawn-protection: true
  spawn-protection-radius: 0
  spawn:
    world: "world"
    x: 0.0
    y: -63.0
    z: 0.0
    pitch: 0.0
    yaw: 0.0
    radius: 8.5
  queue-item:
    slot: 4
    material: BEACON
    name: "&aJoin Game"

# Login world configuration (only effective when work-mode: WORLD)
login-world:
  key: "loginqueue2:login"
  dimension: OVERWORLD
  generator-type: FLAT
  seed: ""
  structures: false
  bonus-chest: false
  hardcore: false
  lock-daytime: true
  disable-weather: true
  disable-mob-spawning: true
  disable-pvp: true
  performance-mode: true
  spawn:
    x: 0.0
    y: 64.0
    z: 0.0
    pitch: 0.0
    yaw: 0.0

# WORLD mode command whitelist configuration
world-mode:
  allowed-commands: []
  monitor-interval: 5

# Server status scoreboard configuration
scoreboard:
  enabled: false
  rotate-interval: 10
  servers:
    - "main"
    - "main1"
    - "main2"
```

### LoginQueue2Online (Subserver)

Edit `plugins/LoginQueue2Online/config.yml`:

```yaml
# Current subserver name in proxy (must match the name configured in LS2)
server-name: "main"

# Interval for broadcasting server info to proxy (seconds)
refresh-interval: 5
```

---

## Commands

### LoginQueue2 Main Plugin (Login Server)

| Command | Permission | Description |
|---------|------------|-------------|
| `/logseq skip [player]` | `loginqueue2.admin.skip` | Skip queue and send player directly to main server |
| `/logseq list` | `loginqueue2.admin.list` | Show current queue list |
| `/logseq status` | `loginqueue2.admin.status` | Show main server status (online count, load, etc.) |
| `/logseq refresh` | `loginqueue2.admin.refresh` | Manually refresh main server status cache |
| `/logseq reload` | `loginqueue2.admin.reload` | Reload configuration and language files |
| `/logseq debug` | `loginqueue2.admin.debug` | Toggle debug mode (output detailed logs) |
| `/logseq info` | `loginqueue2.admin.info` | View detailed info of all main servers |
| `/logseq help` | - | Show help information |
| `/join` | - | Manually join the queue (used when auto-queue is disabled) |
| `/register <password> <confirm>` | - | Register account (when built-in auth is enabled) |
| `/login <password>` | - | Login account (when built-in auth is enabled) |
| `/changepassword <old> <new>` | - | Change password (when built-in auth is enabled) |

**Command aliases**: `/ls` and `/lq` are aliases for `/logseq`; `/changepw` is an alias for `/changepassword`

### LoginQueue2BC (BungeeCord Proxy)

| Command | Permission | Description |
|---------|------------|-------------|
| `/lsbc reload` | `loginqueue2bc.admin` | Reload configuration file |
| `/lsbc debug` | `loginqueue2bc.admin` | Toggle debug mode |
| `/lsbc help` | `loginqueue2bc.admin` | Show help information |

**Command alias**: `/loginqueuebc`

### LoginQueue2VC (Velocity Proxy)

| Command | Permission | Description |
|---------|------------|-------------|
| `/lsvc reload` | `loginqueue2vc.admin` | Reload configuration file |
| `/lsvc debug` | `loginqueue2vc.admin` | Toggle debug mode |
| `/lsvc help` | `loginqueue2vc.admin` | Show help information |

**Command alias**: `/loginqueuevc`

### LoginQueue2Limbo (Limbo Login Server)

Commands are identical to the main plugin. Supports `/logseq`, `/ls`, and `/lq` aliases.

### LoginQueue2Online (Subserver)

This plugin has no commands. It runs automatically after startup and reports server status to the login server via UDP.

---

## Permissions

### LoginQueue2

| Permission | Description |
|------------|-------------|
| `loginqueue2.admin.skip` | Allow using `/logseq skip` to bypass queue |
| `loginqueue2.admin.list` | Allow viewing the queue list |
| `loginqueue2.admin.status` | Allow viewing server status |
| `loginqueue2.admin.refresh` | Allow manually refreshing server status |
| `loginqueue2.admin.reload` | Allow reloading configuration |
| `loginqueue2.admin.debug` | Allow toggling debug mode |
| `loginqueue2.admin.info` | Allow viewing detailed server info |
| `loginqueue2.admin.bypass` | Bypass login server restrictions (movement, interaction, etc.) |
| `loginqueue2.vip` | VIP queue priority |
| `loginqueue2.priority` | Priority queue permission |

---

## Detailed Plugin Features

### LoginQueue2 (Main Plugin)

**Platform**: Spigot / Paper / Folia 1.13+
**Install Location**: Login Server (Lobby)
**Required**: **Yes**

**Core Features**:
- **Login Queue Management**: Controls player entry order into main servers with multiple priority sorting rules
- **Load Balancing**: Automatically selects the least loaded server in multi-main server environments
- **Player Restrictions**: Restrict movement, interaction, block breaking for queued players
- **Dimension Protection**: Prevents players from entering Nether or End, disables portals
- **Spawn Protection**: Disables explosions, cancels damage, disables PVP
- **Performance-Saving Mode**: Disables mob spawning, time flow, and weather changes
- **UDP Status Sync**: Retrieves subserver status directly via UDP without requiring a proxy plugin
- **Dual-Mode Support**: Supports both BungeeCord native channels and custom channels for player transfers
- **Authentication System**: Built-in optional register/login/password change functionality, with AuthMe compatibility mode
- **WORLD Mode**: Creates a login world within a single server, no proxy required

**Load Balancing Strategies**:
- `LEAST_PLAYERS`: Select server with fewest online players
- `LEAST_LOAD`: Select server with lowest load percentage
- `ROUND_ROBIN`: Rotate through servers in order
- `RANDOM`: Randomly select a server

**Priority Rules** (supports custom weights):
- `permission:node[:weight]` — Players with the specified permission
- `name:playerName[:weight]` — Specific player name (exact match)
- `uuid:playerUUID[:weight]` — Specific player UUID
- `regex:pattern[:weight]` — Player name matches regex pattern
- `group:groupName[:weight]` — Players with the specified permission group

### LoginQueue2Limbo (Limbo Login Server Plugin)

**Platform**: Limbo
**Install Location**: Limbo Server
**Required**: Required when using Limbo as the login server

**Core Features**: Identical to the main plugin, including queue management, load balancing, player restrictions, UDP sync, BungeeCord/Velocity compatibility, beacon queuing, etc. Adapted for the Limbo platform API.

### LoginQueue2Online (Subserver Status Reporting Plugin)

**Platform**: Spigot / Paper / Folia 1.14+
**Install Location**: Each Main Server
**Required**: Recommended in multi-main server environments for accurate TPS and online status

**Core Features**:
- Start UDP server to listen for queries from the login server
- Real-time reporting of online count, max capacity, online status, and **TPS**
- AES encrypted communication with pre-shared key support
- Automatically broadcast status to the login server at regular intervals

**Characteristics**: No commands, no configuration interface, runs automatically after configuration

> **Why is it needed?** The main plugin can obtain subserver online counts through BungeeCord native channels, but **cannot obtain remote server TPS**. `LoginQueue2Online` reports TPS and other detailed status to the login server via UDP, enabling the `LEAST_LOAD` balancing strategy to work properly. If you only use `LEAST_PLAYERS` or `ROUND_ROBIN` strategies and don't need TPS monitoring, this plugin can theoretically be omitted.

### LoginQueue2BC (BungeeCord Proxy Plugin)

**Platform**: BungeeCord
**Install Location**: BungeeCord Proxy
**Required**: **Optional**, only install when proxy-side channel features are needed

**Core Features**:
- Listen for custom plugin messaging channels
- Handle player cross-server transfer requests (`ConnectOther` / `ConnectRequest`)
- Handle server info query requests (`ServerInfo`)
- Forward subserver status info to the login server
- IP limits and SQLite database logging

**Applicable Scenario**: Install when using BC channel priority mode and requiring proxy-side intervention. In most cases, UDP priority mode can fully replace it.

### LoginQueue2VC (Velocity Proxy Plugin)

**Platform**: Velocity 3.x
**Install Location**: Velocity Proxy
**Required**: **Optional**, only install when Velocity proxy-side channel features are needed

**Core Features**: Same functionality as LS2BC, adapted for the Velocity platform.

**Applicable Scenario**: Install when using Velocity proxy with BC channel priority mode. Similarly, UDP priority mode can replace it in most cases.

---

## Messaging Channels

| Channel | Description | Usage Scenario |
|---------|-------------|----------------|
| `BungeeCord` | BungeeCord native channel for direct player transfers | UDP priority mode (no extra plugins needed) |
| `loginqueue2:connectother` | Notify proxy to transfer specified player to target server | BC priority mode (requires BC/VC plugin) |
| `loginqueue2:connectrequest` | Player actively requests connection to target server | BC priority mode (requires BC/VC plugin) |
| `loginqueue2:serverinfo` | Query / report server status information | BC priority mode (requires BC/VC plugin) |
| `loginqueue2:loginsuccess` | Login success notification channel | Authentication system related |
| `UDP` | Direct UDP communication for server status | UDP priority mode (requires Online plugin) |

---

## Requirements

- Java 8+ (LoginQueue2VC requires Java 17)
- Maven 3.6+
- WORLD mode requires the [Worlds](https://github.com/TheNextLvl-net/worlds) plugin

---

## License

MIT License
