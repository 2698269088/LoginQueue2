# LoginQueue2

### Note: The plugin was originally named LoginSequence2, but it has now been renamed to LoginQueue2.

> **A next-generation Minecraft login queue solution**, providing high-performance player queuing, load balancing, and cross-server transfer capabilities for network servers. Supports multiple platform combinations including Spigot/Paper, BungeeCord, Velocity, and Limbo to meet the deployment needs of various server scales.

---

## Important Notice

### One Plugin Handles Almost Everything

Since **version 1.3**, **a single `LoginQueue2` / `LoginQueue2Limbo` main plugin can handle 99% of the functionality**, allowing for a nearly perfect login queue experience without needing the Online/BC/VC companion plugins.

- **Single Server Mode**: Just install the `LoginQueue2` main plugin on your login server (Lobby). That's it.
- **Network Mode (UDP Priority - Recommended)**: Install `LoginQueue2` on the login server and `LoginQueue2Online` on each main server for status reporting. No BC/VC proxy plugins required.
- **The Only Exception**: Remote main server **TPS (ticks per second)** cannot be obtained by a single server alone. It requires the `LoginQueue2Online` plugin (via UDP) or a proxy plugin (BC/VC) to provide complete server load data. If you don't need precise TPS monitoring, the main plugin alone is fully sufficient.

> **Note**: Unless new features are required, the BC/VC and Online companion plugins typically do not need to be updated. These plugins are functionally stable and only need to remain compatible with the main plugin version.

---

## Project Structure

```
LoginQueue2/
├── pom.xml                          # Root aggregator POM
├── LoginQueue2/                     # Main plugin (Spigot/Paper)
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
| **LoginQueue2** | Spigot/Paper 1.13+ | Main plugin providing login queue, player restrictions, commands, load balancing, and all core features | **Yes** |
| **LoginQueue2Limbo** | Limbo | Lightweight login server plugin with identical features to the main plugin, adapted for Limbo | As needed |
| **LoginQueue2Online** | Spigot/Paper 1.14+ | Subserver plugin reporting online status and TPS via UDP | Recommended (for multi-main) |
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
├── LoginQueue2-1.3.jar
├── LoginQueue2BC-1.3.jar
├── LoginQueue2Online-1.3.jar
├── LoginQueue2VC-1.3.jar
└── LoginQueue2Limbo-1.3.jar
```

---

## Installation Guide

### Single Server Mode (Simplest)

Only **1 plugin** needed:

1. Place `LoginQueue2-1.3.jar` into the `plugins/` folder of your login server (Lobby).
2. Done. No other plugins required — queue, restrictions, and transfers all work out of the box.

### Network Mode (UDP Priority - Recommended)

Only **2 plugins** needed, **no BC/VC proxy plugins required**:

1. **Login Server (Lobby)**: Place `LoginQueue2-1.3.jar` (Spigot/Paper) or `LoginQueue2Limbo-1.3.jar` (Limbo)
2. **Main Servers (Main1, Main2...)**: Place `LoginQueue2Online-1.3.jar`

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

1. **Login Server (Lobby)**: `LoginQueue2-1.3.jar` or `LoginQueue2Limbo-1.3.jar`
2. **Main Servers (Main)**: `LoginQueue2Online-1.3.jar` (for TPS reporting)

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

1. **Login Server (Lobby)**: `LoginQueue2-1.3.jar` or `LoginQueue2Limbo-1.3.jar`
2. **BungeeCord/Velocity Proxy**: `LoginQueue2BC-1.3.jar` or `LoginQueue2VC-1.3.jar`
3. **Main Servers (Main)**: `LoginQueue2Online-1.3.jar`

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### Limbo Lightweight Login Server Mode

Place `LoginQueue2Limbo-1.3.jar` into the `plugins/` folder of your Limbo server.

**Limbo Mode Features**:
- Uses Limbo as a lightweight login server with minimal resource usage
- Supports UDP status sync and BungeeCord/Velocity native channels
- Unified beacon click and `/join` command logic
- Identical feature set to the main plugin, adapted for the Limbo API

---

## Configuration

### LoginQueue2 Main Plugin

Edit `plugins/LoginQueue2/config.yml`:

```yaml
# Plugin language (supports zh_CN, zh_TW, en_US)
language: en_US

# Enable BungeeCord channel extension (for cross-server transfers)
enable-bungee-extension: true

# Enable debug logging (outputs detailed plugin runtime information)
debug: false

# UDP server status synchronization
udp-sync:
  # Enable UDP server status sync
  enabled: false
  # Info retrieval priority: BC_CHANNEL uses BungeeCord messaging first, UDP uses UDP first
  priority: BC_CHANNEL
  # UDP request timeout (milliseconds)
  timeout: 3000
  # Pre-shared key (used to encrypt communication keys and prevent eavesdropping)
  planned-key: "loginqueue"
  # Main server list (supports multi-main server load balancing)
  servers:
    - name: "main1"
      host: "127.0.0.1"
      port: 16647
      secret-key: ""
      max-online: 50
    - name: "main2"
      host: "127.0.0.1"
      port: 16648
      secret-key: ""
      max-online: 50

# Login queue configuration
queue:
  # Main server name in BungeeCord (used in single-server mode)
  main-server: "main"
  # Maximum connectable players for main server (used in single-server mode)
  max-online: 50
  # Load balancing strategy: LEAST_PLAYERS, LEAST_LOAD, ROUND_ROBIN, RANDOM
  balance-strategy: LEAST_PLAYERS
  # Sort priority (higher position = higher priority)
  priority:
    - "permission:loginqueue.vip"
    - "permission:loginqueue.priority"
  # Default priority (higher number = higher priority)
  default-priority: 0
  # Refresh interval for main server info to BungeeCord (seconds)
  refresh-interval: 5
  # Refresh interval when main server is offline (seconds)
  offline-refresh-interval: 10
  # Main server connection threshold (0.0 - 1.0), pause admitting new players when exceeded
  threshold: 0.8
  # Restrict movement for players in queue
  restrict-movement: false
  # Enable performance-saving mode (disable mob spawning, time flow, weather changes)
  performance-mode: true
  # Auto-queue players on join
  auto-queue: false
  # Lock duration after player joins (seconds), during which players cannot interact
  lock-time: 3
  # Set gamemode after player joins
  set-gamemode: true
  # Gamemode after player joins (SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR)
  gamemode: ADVENTURE
  # Allow players to interact with blocks (click buttons, open doors, etc.)
  allow-block-interact: false
  # Allow players to place blocks
  allow-block-place: false
  # Allow players to break blocks
  allow-block-break: false
  # Disable Nether dimension (prevent players from entering or teleporting to NETHER)
  disable-nether: true
  # Disable End dimension (prevent players from entering or teleporting to THE_END)
  disable-end: true
  # Disable portals (prevent players from teleporting through any portal)
  disable-portals: true
  # Admins bypass above restrictions (requires loginqueue.admin.bypass permission)
  admin-bypass: true
  # Restrict player activity range (pull back to center if exceeded)
  restrict-range: false
  # Player activity range limit (blocks from spawn point)
  range-limit: 10
  # Spawn protection (disable explosions, cancel player damage, disable PVP)
  spawn-protection: true
  # Spawn protection radius (from spawn point, 0 means entire world is protected)
  spawn-protection-radius: 0
  # Player spawn configuration
  spawn:
    world: "world"
    x: 0.0
    y: -63.0
    z: 0.0
    pitch: 0.0
    yaw: 0.0
    radius: 0
  # Item given when manually queuing
  queue-item:
    slot: 4
    material: BEACON
    name: "&aJoin Game"
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
| `/logseq skip [player]` | `loginqueue.admin.skip` | Skip queue and send player directly to main server |
| `/logseq list` | `loginqueue.admin.list` | Show current queue list |
| `/logseq status` | `loginqueue.admin.status` | Show main server status (online count, load, etc.) |
| `/logseq refresh` | `loginqueue.admin.refresh` | Manually refresh main server status cache |
| `/logseq reload` | `loginqueue.admin.reload` | Reload configuration and language files |
| `/logseq debug` | `loginqueue.admin.debug` | Toggle debug mode (output detailed logs) |
| `/logseq info` | `loginqueue.admin.info` | View detailed info of all main servers |
| `/logseq help` | - | Show help information |
| `/join` | - | Manually join the queue (used when auto-queue is disabled) |

**Command alias**: `/ls` is an alias for `/logseq`

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

Commands are identical to the main plugin. Supports `/logseq` and `/ls` aliases.

### LoginQueue2Online (Subserver)

This plugin has no commands. It runs automatically after startup and reports server status to the login server via UDP.

---

## Permissions

### LoginQueue2

| Permission | Description |
|------------|-------------|
| `loginqueue.admin.skip` | Allow using `/logseq skip` to bypass queue |
| `loginqueue.admin.list` | Allow viewing the queue list |
| `loginqueue.admin.status` | Allow viewing server status |
| `loginqueue.admin.refresh` | Allow manually refreshing server status |
| `loginqueue.admin.reload` | Allow reloading configuration |
| `loginqueue.admin.debug` | Allow toggling debug mode |
| `loginqueue.admin.info` | Allow viewing detailed server info |
| `loginqueue.admin.bypass` | Bypass login server restrictions (movement, interaction, etc.) |
| `loginqueue.vip` | VIP queue priority |
| `loginqueue.priority` | Priority queue permission |

---

## Detailed Plugin Features

### LoginQueue2 (Main Plugin)

**Platform**: Spigot/Paper 1.13+
**Install Location**: Login Server (Lobby)
**Required**: **Yes**

**Core Features**:
- **Login Queue Management**: Controls player entry order into main servers with priority sorting
- **Load Balancing**: Automatically selects the least loaded server in multi-main server environments
- **Player Restrictions**: Restrict movement, interaction, block breaking for queued players
- **Dimension Protection**: Prevents players from entering Nether or End, disables portals
- **Spawn Protection**: Disables explosions, cancels damage, disables PVP
- **Performance-Saving Mode**: Disables mob spawning, time flow, and weather changes
- **UDP Status Sync**: Retrieves subserver status directly via UDP without requiring a proxy plugin
- **Dual-Mode Support**: Supports both BungeeCord native channels and custom channels for player transfers
- **Authentication System**: Built-in optional register/login/password change functionality

**Load Balancing Strategies**:
- `LEAST_PLAYERS`: Select server with fewest online players
- `LEAST_LOAD`: Select server with lowest load percentage
- `ROUND_ROBIN`: Rotate through servers in order
- `RANDOM`: Randomly select a server

### LoginQueue2Limbo (Limbo Login Server Plugin)

**Platform**: Limbo
**Install Location**: Limbo Server
**Required**: Required when using Limbo as the login server

**Core Features**: Identical to the main plugin, including queue management, load balancing, player restrictions, UDP sync, BungeeCord/Velocity compatibility, beacon queuing, etc. Adapted for the Limbo platform API.

### LoginQueue2Online (Subserver Status Reporting Plugin)

**Platform**: Spigot/Paper 1.14+
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
| `UDP` | Direct UDP communication for server status | UDP priority mode (requires Online plugin) |

---

## Requirements

- Java 8+ (LoginQueue2VC requires Java 17)
- Maven 3.6+

---

## License

MIT License
