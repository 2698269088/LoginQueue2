# LoginQueue2

> **提示：插件原名为 LoginSequence2，现已更名为 LoginQueue2。**

新一代 Minecraft 登录队列解决方案，为群组服提供高性能的玩家排队、负载均衡和跨服转移功能。支持 **Spigot、Paper、Folia、Limbo、BungeeCord、Velocity** 多种平台组合，满足不同规模服务器的部署需求。

---

## 核心特性

- **多平台支持**：主插件同时兼容 Spigot、Paper、Folia 服务端；配套插件覆盖 Limbo、BungeeCord、Velocity 平台
- **登录队列管理**：控制玩家进入主服务器的顺序，支持基于权限/名称/UUID/正则/权限组的优先级排序
- **负载均衡**：多主服务器环境下自动选择最优服务器（最少玩家 / 最低负载 / 轮询 / 随机）
- **双工作模式**：
  - **PROXY 模式**：传统群组服架构，通过代理端跳转
  - **WORLD 模式**：单服内创建独立登录世界，无需代理端
- **玩家限制**：限制排队中的玩家移动、交互、破坏方块，支持活动范围限制
- **维度与出生点保护**：禁止进入下界/末地，禁用传送门，保护出生点区域
- **性能节省模式**：禁用生物生成、时间流逝、天气更替
- **UDP 状态同步**：通过 UDP 直接获取子服务器状态，无需代理端插件
- **跨服虚拟排队（/connect）**：子服玩家可通过 `/connect <目标服务器>` 加入登录服的对应服务器队列，排到后由登录服通知子服自动跳转
- **内置认证系统**：可选的注册/登录/改密码功能，同时支持 AuthMe 兼容模式
- **信标排队**：手动排队模式下，玩家点击信标物品加入队列
- **服务器状态计分板**：实时显示各主服务器在线状态

---

## 重要提示

### 单插件即可完成绝大多数功能

- **单服务器模式**：只需在登录服（Lobby）安装 `LoginQueue2` 主插件即可
- **群组服模式（UDP 优先 - 推荐）**：登录服安装 `LoginQueue2`，各主服务器安装 `LoginQueue2Online` 用于状态上报。无需安装 BC/VC 代理端插件
- **唯一例外**：远程主服务器的 **TPS** 信息无法通过单个服务器获取，需要依赖 UDP 状态上报插件（Online）或代理端插件（BC/VC）来提供完整的服务器负载数据。如果你不需要精确的 TPS 监控，单个主插件完全足够

> **提示**：除非新功能需要，否则通常不需要更新 BC/VC 和 Online 端插件。这些配套插件功能稳定，只需保持与主插件版本兼容即可。

### `/connect` 跨服虚拟排队

自 **1.6 版本**起，支持子服玩家通过 `/connect <目标服务器>` 命令加入登录服队列，实现跨服排队。

- **使用条件**：主插件与 `LoginQueue2Online` 子服插件均升级到 1.6+，且两端 UDP 同步与 `/connect` 虚拟排队配置正确
- **子服**：安装 `LoginQueue2Online`，开启 `udp-sync.main-plugin.enabled`，并配置主插件 UDP 地址与密钥
- **主插件**：开启 `udp-sync.connect-queue.enabled`，并在 `udp-sync.servers` 中按子服名称配置 `secret-key`
- **密钥一致性**：子服 `udp-sync.main-plugin.secret-key` 必须和主插件对应服务器条目中的 `secret-key` 完全一致

---

## 项目结构

```
LoginQueue2/
├── pom.xml                          # 根聚合 POM
├── LoginQueue2/                     # 主插件（Spigot/Paper/Folia）
│   └── src/main/java/top/mcocet/loginqueue2/
├── LoginQueue2Limbo/                # Limbo 轻量级登录服配套插件
│   └── src/main/java/top/mcocet/loginqueue2limbo/
├── LoginQueue2Online/               # 子服务器在线状态上报插件（UDP）
│   └── src/main/java/top/mcocet/loginqueue2online/
├── LoginQueue2BC/                   # BungeeCord 代理端配套插件（可选）
│   └── src/main/java/top/mcocet/loginqueue2bc/
└── LoginQueue2VC/                   # Velocity 代理端配套插件（可选）
    └── src/main/java/top/mcocet/loginqueue2vc/
```

## 模块说明

| 模块 | 平台 | 作用 | 是否必需 |
|------|------|------|----------|
| **LoginQueue2** | Spigot / Paper / Folia 1.13+ | 主插件，提供登录队列、玩家限制、指令、负载均衡等全部核心功能 | **是** |
| **LoginQueue2Limbo** | Limbo | 轻量级登录服插件，功能同主插件但适配 Limbo 平台 | 按需 |
| **LoginQueue2Online** | Spigot / Paper / Folia 1.14+ | 子服务器插件，通过 UDP 上报本服务器在线状态与 TPS；支持 `/connect` 跨服虚拟排队 | 推荐（多主服时） |
| **LoginQueue2BC** | BungeeCord | 代理端插件，处理跨服转移和服务器信息查询（BC 通道模式） | 可选 |
| **LoginQueue2VC** | Velocity 3.x | Velocity 代理端插件，功能同 LS2BC | 可选 |

---

## 构建

```bash
mvn clean package
```

构建完成后，所有 jar 文件会自动复制到项目根目录的 `dist/` 文件夹：

```
dist/
├── LoginQueue2-x.x.x.jar
├── LoginQueue2BC-x.x.x.jar
├── LoginQueue2Online-x.x.x.jar
├── LoginQueue2VC-x.x.x.jar
└── LoginQueue2Limbo-x.x.x.jar
```

---

## 安装指南

### 单服务器模式（最简单）

仅需 **1 个插件**：

1. 将 `LoginQueue2-x.x.x.jar` 放入登录服（Lobby）的 `plugins/` 文件夹
2. 完成。无需其他插件，队列、限制、转移功能全部可用

### 群组服模式（UDP 优先 - 推荐）

仅需 **2 个插件**，**无需安装 BC/VC 代理端插件**：

1. **登录服（Lobby）**：放入 `LoginQueue2-x.x.x.jar`（Spigot/Paper/Folia）或 `LoginQueue2Limbo-x.x.x.jar`（Limbo）
2. **主服务器（Main1, Main2...）**：放入 `LoginQueue2Online-x.x.x.jar`

配置 `config.yml`:
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

> **为什么推荐 UDP 优先？** 因为 UDP 通道可以直接在主插件与各子服务器之间通信，完全绕过代理端，部署更简单，性能更好。唯一的额外需求是在各主服务器上安装 `LoginQueue2Online` 来上报 TPS 等状态数据。

### 群组服模式（BungeeCord 原生通道 - 可选）

如果你不想使用 UDP，可以使用 BungeeCord 原生通道转移玩家，但此时仍只需要：

1. **登录服（Lobby）**：`LoginQueue2-x.x.x.jar` 或 `LoginQueue2Limbo-x.x.x.jar`
2. **主服务器（Main）**：`LoginQueue2Online-x.x.x.jar`（用于 TPS 上报）

配置 `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

> 注意：使用 `BC_CHANNEL` 优先模式时，如果需要通过代理端自定义通道进行跨服转移，才需要安装 `LoginQueue2BC` 或 `LoginQueue2VC`。仅使用 BungeeCord 原生 `Connect` 通道时，仍然不需要代理端插件。

### 群组服模式（完整 BC/VC 通道模式 - 可选）

如果你需要代理端插件提供的额外功能（如 IP 限制、数据库记录等）：

1. **登录服（Lobby）**：`LoginQueue2-x.x.x.jar` 或 `LoginQueue2Limbo-x.x.x.jar`
2. **BungeeCord/Velocity 代理**：`LoginQueue2BC-x.x.x.jar` 或 `LoginQueue2VC-x.x.x.jar`
3. **主服务器（Main）**：`LoginQueue2Online-x.x.x.jar`

配置 `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### Limbo 轻量级登录服模式

将 `LoginQueue2Limbo-x.x.x.jar` 放入 Limbo 服务器的 `plugins/` 文件夹。

**Limbo 模式特点**：
- 使用 Limbo 作为轻量级登录服，占用资源极低
- 支持 UDP 状态同步和 BungeeCord/Velocity 原生通道
- 信标点击和 `/join` 命令统一逻辑
- 功能与主插件完全一致，只是适配了 Limbo API

### 单服登录世界模式（WORLD 模式）

无需代理端，在同一服务器内创建独立的登录世界：

1. 安装 [Worlds](https://github.com/TheNextLvl-net/worlds) 插件
2. 将 `LoginQueue2-x.x.x.jar` 放入 `plugins/` 文件夹
3. 修改 `config.yml`：
```yaml
work-mode: WORLD
queue:
  spawn:
    world: "login"  # 登录世界名称，需与 Worlds 插件创建的世界一致
```

**WORLD 模式特点**：
- 无需 BungeeCord/Velocity 代理端，适合单服架构
- 玩家在登录世界排队，放行后传送至主世界
- 登录世界与主世界完全隔离，互不干扰
- 支持所有队列管理和玩家限制功能

---

## 配置

### LoginQueue2 主插件

编辑 `plugins/LoginQueue2/config.yml`：

```yaml
# 插件语言（支持 zh_CN、zh_TW、en_US）
language: zh_CN

# 工作模式: PROXY（代理模式）或 WORLD（登录世界模式）
work-mode: PROXY

# 是否启用 BungeeCord 通道扩展（用于跨服转移，WORLD 模式下无效）
enable-bungee-extension: true

# 是否启用调试日志（输出详细的插件运行信息）
debug: false

# 数据库配置（支持 sqlite 和 mysql）
database:
  type: sqlite

# MySQL 配置（仅当 database.type 为 mysql 时生效）
mysql:
  host: localhost
  port: 3306
  database: loginqueue2
  username: root
  password: ""
  table-prefix: lq2_
  use-ssl: false
  auto-reconnect: true

# 认证系统配置
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

# UDP 服务器信息同步配置
udp-sync:
  enabled: false
  priority: BC_CHANNEL
  timeout: 3000
  planned-key: "loginqueue2"
  # /connect 跨服虚拟排队配置
  connect-queue:
    # 是否启用接收子服的 /connect 虚拟排队请求
    enabled: false
    # 主插件 UDP 服务端监听端口（子服通过此端口发送请求）
    server-port: 16648
    # 队列状态广播间隔（秒），0 表示不广播
    status-interval: 3
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

# 登录队列配置
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
    name: "&a加入游戏"

# 登录世界配置（仅在 work-mode: WORLD 时生效）
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

# WORLD 模式下命令白名单配置
world-mode:
  allowed-commands: []
  monitor-interval: 5

# 服务器状态计分板配置
scoreboard:
  enabled: false
  rotate-interval: 10
  servers:
    - "main"
    - "main1"
    - "main2"
```

### LoginQueue2Online（子服务器）

编辑 `plugins/LoginQueue2Online/config.yml`：

```yaml
# 当前子服务器在代理端/主插件中的名称（必须和 LS2 配置中的名称一致）
server-name: "main"

# 向代理端广播服务器信息的周期（秒）
refresh-interval: 5

# UDP 服务器信息同步配置
udp-sync:
  # 是否启用 UDP 服务器信息响应
  enabled: true
  # UDP 监听端口（登录服向此端口查询状态）
  port: 16647
  # SHA256 通信密钥（留空则随机生成，需要与主插件配置保持一致）
  secret-key: ""
  # 计划好的预共享密钥（用于加密传输通信密钥）
  planned-key: "loginqueue2"
  # 主插件 UDP 连接配置（用于 /connect 虚拟排队）
  main-plugin:
    # 是否启用 /connect 虚拟排队
    enabled: true
    # 主插件（LQ2/LQ2Limbo）UDP 服务端地址
    host: "127.0.0.1"
    # 主插件 UDP 服务端端口
    port: 16648
    # 通信密钥，需要与主插件 udp-sync.servers 中本服务器的 secret-key 一致
    secret-key: ""
    # 请求超时时间（毫秒）
    timeout: 3000

# 服务器列表（用于 /connect 指令 Tab 补全）
server-list:
  - "lobby"
  - "main"
  - "minigames"

messages:
  # 虚拟排队状态提示
  virtual-queue-status: "&a[队列] &f当前排在第 &e{position} &f位，目标服在线 &e{online}&f/&e{max}&f。"
```

---

## 指令

### LoginQueue2 主插件（登录服）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/logseq skip [玩家名]` | `loginqueue2.admin.skip` | 跳过排队，直接将玩家送入主服务器 |
| `/logseq list` | `loginqueue2.admin.list` | 显示当前排队玩家列表 |
| `/logseq status` | `loginqueue2.admin.status` | 显示主服务器状态（在线人数、负载等） |
| `/logseq refresh` | `loginqueue2.admin.refresh` | 手动刷新主服务器状态缓存 |
| `/logseq reload` | `loginqueue2.admin.reload` | 重载配置文件和语言文件 |
| `/logseq debug` | `loginqueue2.admin.debug` | 切换调试模式（输出详细日志） |
| `/logseq info` | `loginqueue2.admin.info` | 查看所有主服务器详细信息 |
| `/logseq help` | - | 显示帮助信息 |
| `/join` | - | 手动加入排队队列（非自动排队模式时使用） |
| `/register <密码> <确认密码>` | - | 注册账号（内置认证启用时） |
| `/login <密码>` | - | 登录账号（内置认证启用时） |
| `/changepassword <旧密码> <新密码>` | - | 修改密码（内置认证启用时） |

**指令别名**: `/ls`、`/lq` 是 `/logseq` 的别名；`/changepw` 是 `/changepassword` 的别名

### LoginQueue2BC（BungeeCord 代理端）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/lsbc reload` | `loginqueue2bc.admin` | 重载配置文件 |
| `/lsbc debug` | `loginqueue2bc.admin` | 切换调试模式 |
| `/lsbc help` | `loginqueue2bc.admin` | 显示帮助信息 |

**指令别名**: `/loginqueuebc`

### LoginQueue2VC（Velocity 代理端）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/lsvc reload` | `loginqueue2vc.admin` | 重载配置文件 |
| `/lsvc debug` | `loginqueue2vc.admin` | 切换调试模式 |
| `/lsvc help` | `loginqueue2vc.admin` | 显示帮助信息 |

**指令别名**: `/loginqueuevc`

### LoginQueue2Limbo（Limbo 登录服）

指令与主插件完全一致，支持 `/logseq`、`/ls`、`/lq` 别名。

### LoginQueue2Online（子服务器）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/connect <服务器名> [玩家名]` | `loginqueue2online.connect` | 加入目标服务器队列；若启用虚拟排队，则通过 UDP 向主插件排队 |
| `/connect <服务器名> <玩家名>` | `loginqueue2online.connect.others` | 将其他玩家加入目标服务器队列 |

> 该插件除 `/connect` 外无其他指令。启动后自动通过 UDP 向登录服上报服务器状态信息。

---

## 权限节点

### LoginQueue2

| 权限 | 说明 |
|------|------|
| `loginqueue2.admin.skip` | 允许使用 `/logseq skip` 跳过排队 |
| `loginqueue2.admin.list` | 允许查看排队列表 |
| `loginqueue2.admin.status` | 允许查看服务器状态 |
| `loginqueue2.admin.refresh` | 允许手动刷新服务器状态 |
| `loginqueue2.admin.reload` | 允许重载配置 |
| `loginqueue2.admin.debug` | 允许切换调试模式 |
| `loginqueue2.admin.info` | 允许查看服务器详细信息 |
| `loginqueue2.admin.bypass` | 不受登录服限制（移动、交互等） |
| `loginqueue2.vip` | VIP 排队优先级 |
| `loginqueue2.priority` | 优先排队权限 |

### LoginQueue2Online

| 权限 | 说明 |
|------|------|
| `loginqueue2online.connect` | 允许使用 `/connect` 加入目标服务器队列 |
| `loginqueue2online.connect.others` | 允许为其他玩家使用 `/connect` |

---

## 各插件功能详解

### LoginQueue2（主插件）

**运行平台**: Spigot / Paper / Folia 1.13+
**安装位置**: 登录服（Lobby）
**必需性**: **必需**

**核心功能**:
- **登录队列管理**：控制玩家进入主服务器的顺序，支持多种优先级排序规则
- **负载均衡**：多主服务器环境下自动选择负载最低的服务器
- **玩家限制**：限制排队中的玩家移动、交互、破坏方块等
- **维度保护**：禁止玩家进入下界、末地，禁用传送门
- **出生点保护**：禁止爆炸、取消伤害、禁止 PVP
- **性能节省模式**：禁用生物生成、时间流逝、天气更替
- **UDP 状态同步**：通过 UDP 直接获取子服务器状态，无需代理端插件
- **双模式支持**：支持 BungeeCord 原生通道或自定义通道转移玩家
- **认证系统**：内置可选的注册/登录/改密码功能，支持 AuthMe 兼容模式
- **WORLD 模式**：单服内创建登录世界，无需代理端

**负载均衡策略**:
- `LEAST_PLAYERS`: 选择在线人数最少的服务器
- `LEAST_LOAD`: 选择负载百分比最低的服务器
- `ROUND_ROBIN`: 轮询选择服务器
- `RANDOM`: 随机选择服务器

**优先级规则**（支持自定义权重）:
- `permission:权限节点[:权重]` — 拥有指定权限的玩家
- `name:玩家名[:权重]` — 指定玩家名（精确匹配）
- `uuid:玩家UUID[:权重]` — 指定玩家 UUID
- `regex:正则表达式[:权重]` — 玩家名匹配正则表达式
- `group:权限组名[:权重]` — 拥有指定权限组的玩家

### LoginQueue2Limbo（Limbo 登录服插件）

**运行平台**: Limbo
**安装位置**: Limbo 服务器
**必需性**: 使用 Limbo 作为登录服时必需

**核心功能**: 功能与主插件完全一致，包括队列管理、负载均衡、玩家限制、UDP 同步、BungeeCord/Velocity 兼容、信标排队等。只是适配了 Limbo 平台 API。

### LoginQueue2Online（子服务器状态上报插件）

**运行平台**: Spigot / Paper / Folia 1.14+
**安装位置**: 各主服务器（Main）
**必需性**: 多主服务器环境下推荐安装，用于获取精确的 TPS 和在线状态

**核心功能**:
- 启动 UDP 服务端，监听登录服的查询请求
- 实时上报本服务器的在线人数、最大人数、在线状态、**TPS**
- 使用 AES 加密通信，支持预共享密钥
- 自动定期向登录服广播状态信息
- **/connect 跨服虚拟排队**：子服玩家可通过 `/connect <目标服务器>` 向登录服主插件发起虚拟排队请求，排到后由登录服通知子服完成自动跳转

**特点**: 除 `/connect` 外无其他指令，配置后自动运行

> **为什么需要它？** 主插件可以通过 BungeeCord 原生通道获取子服务器的在线人数，但 **无法获取远程服务器的 TPS**。`LoginQueue2Online` 通过 UDP 将 TPS 等详细状态上报给登录服，使负载均衡策略 `LEAST_LOAD` 能够正常工作。如果你只使用 `LEAST_PLAYERS` 或 `ROUND_ROBIN` 策略，且不需要 TPS 监控，理论上可以省略此插件。

### LoginQueue2BC（BungeeCord 代理端插件）

**运行平台**: BungeeCord
**安装位置**: BungeeCord 代理端
**必需性**: **可选**，仅在需要代理端通道功能时安装

**核心功能**:
- 监听自定义插件消息通道
- 处理玩家跨服转移请求（`ConnectOther` / `ConnectRequest`）
- 处理服务器信息查询请求（`ServerInfo`）
- 将子服务器状态信息转发给登录服
- IP 限制与 SQLite 数据库记录

**适用场景**: 使用 BC 通道优先模式且需要代理端介入时安装。大多数情况下，使用 UDP 优先模式即可完全替代。

### LoginQueue2VC（Velocity 代理端插件）

**运行平台**: Velocity 3.x
**安装位置**: Velocity 代理端
**必需性**: **可选**，仅在 Velocity 环境下需要代理端通道功能时安装

**核心功能**: 功能同 LS2BC，适配 Velocity 平台。

**适用场景**: 使用 Velocity 代理且需要 BC 通道优先模式时安装。同样，大多数情况下 UDP 优先模式即可替代。

---

## 消息通道

| 通道 | 说明 | 使用场景 |
|------|------|----------|
| `BungeeCord` | BungeeCord 原生通道，用于直接转移玩家 | UDP 优先模式（无需额外插件） |
| `loginqueue2:connectother` | 通知代理端将指定玩家转移到目标服务器 | BC 优先模式（需要 BC/VC 插件） |
| `loginqueue2:connectrequest` | 玩家主动请求连接到目标服务器 | BC 优先模式（需要 BC/VC 插件） |
| `loginqueue2:serverinfo` | 查询/上报服务器状态信息 | BC 优先模式（需要 BC/VC 插件） |
| `loginqueue2:loginsuccess` | 登录成功通知通道 | 认证系统相关 |
| `UDP` | 直接 UDP 通信获取服务器状态 | UDP 优先模式（需要 Online 插件） |

---

## 依赖

- Java 8+（LoginQueue2VC 需要 Java 17）
- Maven 3.6+
- WORLD 模式需要 [Worlds](https://github.com/TheNextLvl-net/worlds) 插件

---

## 许可证

MIT License
