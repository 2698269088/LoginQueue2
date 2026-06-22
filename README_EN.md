# LoginSequence2

LoginSequence2 is a Minecraft server login queue system supporting Spigot/Paper subservers, BungeeCord, Velocity proxies, and Limbo lightweight login servers. Built with Maven multi-module aggregation, it manages five companion plugins in a unified project.
This plugin suite replaces the legacy LoginSequence plugin, having been completely refactored with significantly improved code quality and efficiency.

> **LoginSequence2** is a next-generation Minecraft login queue solution, providing high-performance player queuing, load balancing, and cross-server transfer capabilities for network servers. Supports multiple platform combinations including Spigot/Paper, BungeeCord, Velocity, and Limbo to meet the deployment needs of various server scales.

## Project Structure

```
LoginSequence/
├── pom.xml                          # Root aggregator POM
├── LoginSequence2/                  # Main plugin (Spigot/Paper)
│   └── src/main/java/top/mcocet/loginsequence2/
├── LoginSequence2BC/                # BungeeCord proxy companion plugin
│   └── src/main/java/top/mcocet/loginsequence2bc/
├── LoginSequence2Online/            # Subserver status reporting plugin
│   └── src/main/java/top/mcocet/loginsequence2online/
├── LoginSequence2VC/                # Velocity proxy companion plugin
│   └── src/main/java/top/mcocet/loginsequence2vc/
└── LoginSequence2Limbo/             # Limbo login server companion plugin
    └── src/main/java/top/mcocet/loginsequence2limbo/
```

## Module Overview

| Module | Platform | Purpose |
|--------|----------|---------|
| LoginSequence2 | Spigot/Paper 1.13+ | Main plugin providing login queue, player restrictions, commands, and more |
| LoginSequence2BC | BungeeCord | Proxy plugin handling cross-server transfers and server info queries |
| LoginSequence2Online | Spigot/Paper 1.14+ | Subserver plugin reporting online status to the main server |
| LoginSequence2VC | Velocity 3.x | Velocity proxy plugin with same functionality as LS2BC |
| LoginSequence2Limbo | Limbo | Lightweight login server plugin providing queue and status sync |

## Build

```bash
mvn clean package
```

After building, all JAR files are automatically copied to the `dist/` folder at the project root:

```
dist/
├── LoginSequence2-1.0.1.jar
├── LoginSequence2BC-1.0.1.jar
├── LoginSequence2Online-1.0.1.jar
├── LoginSequence2VC-1.0.1.jar
└── LoginSequence2Limbo-1.0.1.jar
```

## Installation

### Basic Installation (Single Server)

Place `LoginSequence2-1.0.1.jar` into the `plugins/` folder of your login server (Lobby).

### Basic Installation (Limbo Login Server)

Place `LoginSequence2Limbo-1.0.1.jar` into the `plugins/` folder of your Limbo server.

**Limbo Mode Features**:
- Uses Limbo as a lightweight login server with minimal resource usage
- Supports UDP status sync and BungeeCord/Velocity native channels
- Unified beacon click and `/join` command logic

### Network Installation (BungeeCord + UDP Priority Mode - Recommended)

**No BC plugin required** — uses UDP for direct status sync and BungeeCord native channels for player transfers.

1. **Login Server (Lobby)**: Place `LoginSequence2-1.0.1.jar` (Spigot/Paper) or `LoginSequence2Limbo-1.0.1.jar` (Limbo)
2. **Main Servers (Main1, Main2...)**: Place `LoginSequence2Online-1.0.1.jar`

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

### Network Installation (BungeeCord + BC Priority Mode)

**BC plugin required** — uses BungeeCord custom plugin messaging channels.

1. **Login Server (Lobby)**: Place `LoginSequence2-1.0.1.jar` (Spigot/Paper) or `LoginSequence2Limbo-1.0.1.jar` (Limbo)
2. **BungeeCord Proxy**: Place `LoginSequence2BC-1.0.1.jar`
3. **Main Servers (Main)**: Place `LoginSequence2Online-1.0.1.jar`

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### Network Installation (Velocity + BC Priority Mode)

1. **Login Server (Lobby)**: Place `LoginSequence2-1.0.1.jar` (Spigot/Paper) or `LoginSequence2Limbo-1.0.1.jar` (Limbo)
2. **Velocity Proxy**: Place `LoginSequence2VC-1.0.1.jar`
3. **Main Servers (Main)**: Place `LoginSequence2Online-1.0.1.jar`

Configure `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

## Configuration

### LoginSequence2 Main Plugin

Edit `plugins/LoginSequence/config.yml`:

```yaml
# Plugin language (supports zh_CN, zh_TW, en_US)
language: en_US

# Enable BungeeCord channel extension (requires proxy plugin companion)
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
  planned-key: "loginsequence"
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
    - "permission:loginsequence.vip"
    - "permission:loginsequence.priority"
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
  # Admins bypass above restrictions (requires loginsequence.admin.bypass permission)
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

### LoginSequence2Online (Subserver)

Edit `plugins/LoginSequence2Online/config.yml`:

```yaml
# Current subserver name in proxy (must match the name configured in LS2)
server-name: "main"

# Interval for broadcasting server info to proxy (seconds)
refresh-interval: 5
```

## Commands

### LoginSequence2 Main Plugin (Login Server)

| Command | Permission | Description |
|---------|------------|-------------|
| `/logseq skip [player]` | `loginsequence.admin.skip` | Skip queue and send player directly to main server |
| `/logseq list` | `loginsequence.admin.list` | Show current queue list |
| `/logseq status` | `loginsequence.admin.status` | Show main server status (online count, load, etc.) |
| `/logseq refresh` | `loginsequence.admin.refresh` | Manually refresh main server status cache |
| `/logseq reload` | `loginsequence.admin.reload` | Reload configuration and language files |
| `/logseq debug` | `loginsequence.admin.debug` | Toggle debug mode (output detailed logs) |
| `/logseq info` | `loginsequence.admin.info` | View detailed info of all main servers |
| `/logseq help` | - | Show help information |
| `/join` | - | Manually join the queue (used when auto-queue is disabled) |

**Command alias**: `/ls` is an alias for `/logseq`

### LoginSequence2BC (BungeeCord Proxy)

| Command | Permission | Description |
|---------|------------|-------------|
| `/lsbc reload` | `loginsequence2bc.admin` | Reload configuration file |
| `/lsbc debug` | `loginsequence2bc.admin` | Toggle debug mode |
| `/lsbc help` | `loginsequence2bc.admin` | Show help information |

**Command alias**: `/loginsequencebc`

### LoginSequence2VC (Velocity Proxy)

| Command | Permission | Description |
|---------|------------|-------------|
| `/lsvc reload` | `loginsequence2vc.admin` | Reload configuration file |
| `/lsvc debug` | `loginsequence2vc.admin` | Toggle debug mode |
| `/lsvc help` | `loginsequence2vc.admin` | Show help information |

**Command alias**: `/loginsequencevc`

### LoginSequence2Online (Subserver)

This plugin has no commands. It runs automatically after startup and reports server status to the login server via UDP.

## Permissions

### LoginSequence2

| Permission | Description |
|------------|-------------|
| `loginsequence.admin.skip` | Allow using `/logseq skip` to bypass queue |
| `loginsequence.admin.list` | Allow viewing the queue list |
| `loginsequence.admin.status` | Allow viewing server status |
| `loginsequence.admin.refresh` | Allow manually refreshing server status |
| `loginsequence.admin.reload` | Allow reloading configuration |
| `loginsequence.admin.debug` | Allow toggling debug mode |
| `loginsequence.admin.info` | Allow viewing detailed server info |
| `loginsequence.admin.bypass` | Bypass login server restrictions (movement, interaction, etc.) |
| `loginsequence.vip` | VIP queue priority |
| `loginsequence.priority` | Priority queue permission |

## Detailed Plugin Features

### LoginSequence2 (Main Plugin)

**Platform**: Spigot/Paper 1.13+
**Install Location**: Login Server (Lobby)

**Core Features**:
- **Login Queue Management**: Controls player entry order into main servers with priority sorting
- **Load Balancing**: Automatically selects the least loaded server in multi-main server environments
- **Player Restrictions**: Restrict movement, interaction, block breaking for queued players
- **Dimension Protection**: Prevents players from entering Nether or End, disables portals
- **Spawn Protection**: Disables explosions, cancels damage, disables PVP
- **Performance-Saving Mode**: Disables mob spawning, time flow, and weather changes
- **UDP Status Sync**: Retrieves subserver status directly via UDP without requiring a proxy plugin
- **Dual-Mode Support**: Supports both BungeeCord native channels and custom channels for player transfers

**Load Balancing Strategies**:
- `LEAST_PLAYERS`: Select server with fewest online players
- `LEAST_LOAD`: Select server with lowest load percentage
- `ROUND_ROBIN`: Rotate through servers in order
- `RANDOM`: Randomly select a server

### LoginSequence2BC (BungeeCord Proxy Plugin)

**Platform**: BungeeCord
**Install Location**: BungeeCord Proxy

**Core Features**:
- Listen for custom plugin messaging channels
- Handle player cross-server transfer requests (`ConnectOther` / `ConnectRequest`)
- Handle server info query requests (`ServerInfo`)
- Forward subserver status info to the login server

**Applicable Scenario**: Required when using BC channel priority mode

### LoginSequence2VC (Velocity Proxy Plugin)

**Platform**: Velocity 3.x
**Install Location**: Velocity Proxy

**Core Features**: Same functionality as LS2BC, adapted for Velocity platform

**Applicable Scenario**: Required when using Velocity proxy with BC channel priority mode

### LoginSequence2Online (Subserver Status Reporting Plugin)

**Platform**: Spigot/Paper 1.14+
**Install Location**: Each Main Server

**Core Features**:
- Start UDP server to listen for queries from the login server
- Real-time reporting of online count, max capacity, and online status
- AES encrypted communication with pre-shared key support
- Automatically broadcast status to the login server at regular intervals

**Characteristics**: No commands, no configuration interface, runs automatically after configuration

## Messaging Channels

| Channel | Description | Usage Scenario |
|---------|-------------|----------------|
| `BungeeCord` | BungeeCord native channel for direct player transfers | UDP priority mode |
| `loginsequence:connectother` | Notify proxy to transfer specified player to target server | BC priority mode |
| `loginsequence:connectrequest` | Player actively requests connection to target server | BC priority mode |
| `loginsequence:serverinfo` | Query / report server status information | BC priority mode |

## Requirements

- Java 8+ (LoginSequence2VC requires Java 17)
- Maven 3.6+

## License

MIT License
