# LoginQueue2

### 提示：插件原名为 LoginSequence2，现已更名为 LoginQueue2。

> **新一代 Minecraft 登录队列解决方案**，为群组服提供高性能的玩家排队、负载均衡和跨服转移功能。支持 Spigot/Paper、BungeeCord、Velocity 和 Limbo 多种平台组合，满足不同规模服务器的部署需求。

---

## 重要提示

### 单插件即可完成绝大多数功能

自 **1.3 版本**起，**仅靠一个 `LoginQueue2/LoginQueue2Limbo` 主插件就可以完成 99% 的功能**，无需安装 Online/BC/VC 等配套插件即可实现接近完美的登录队列体验。

- **单服务器模式**：只需在登录服（Lobby）安装 `LoginQueue2` 主插件即可。
- **群组服模式（UDP 优先 - 推荐）**：登录服安装 `LoginQueue2`，各主服务器安装 `LoginQueue2Online` 用于状态上报。无需安装 BC/VC 代理端插件。
- **唯一例外**：远程主服务器的 **TPS（每秒 ticks）** 信息无法通过单个服务器获取，需要依赖代理端插件（BC/VC）或 UDP 状态上报插件（Online）来提供完整的服务器负载数据。如果你不需要精确的 TPS 监控，单个主插件完全足够。

> **提示**：除非新功能需要，否则通常不需要更新 BC/VC 和 Online 端插件。这些配套插件功能稳定，只需保持与主插件版本兼容即可。

---

## 项目结构

```
LoginQueue2/
├── pom.xml                          # 根聚合 POM
├── LoginQueue2/                     # 主插件（Spigot/Paper）
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
| **LoginQueue2** | Spigot/Paper 1.13+ | 主插件，提供登录队列、玩家限制、指令、负载均衡等全部核心功能 | **是** |
| **LoginQueue2Limbo** | Limbo | 轻量级登录服插件，功能同主插件但适配 Limbo 平台 | 按需 |
| **LoginQueue2Online** | Spigot/Paper 1.14+ | 子服务器插件，通过 UDP 上报本服务器在线状态与 TPS | 推荐（多主服时） |
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
├── LoginQueue2-1.3.jar
├── LoginQueue2BC-1.3.jar
├── LoginQueue2Online-1.3.jar
├── LoginQueue2VC-1.3.jar
└── LoginQueue2Limbo-1.3.jar
```

---

## 安装指南

### 单服务器模式（最简单）

仅需 **1 个插件**：

1. 将 `LoginQueue2-1.3.jar` 放入登录服（Lobby）的 `plugins/` 文件夹。
2. 完成。无需其他插件，队列、限制、转移功能全部可用。

### 群组服模式（UDP 优先 - 推荐）

仅需 **2 个插件**，**无需安装 BC/VC 代理端插件**：

1. **登录服（Lobby）**：放入 `LoginQueue2-1.3.jar`（Spigot/Paper）或 `LoginQueue2Limbo-1.3.jar`（Limbo）
2. **主服务器（Main1, Main2...）**：放入 `LoginQueue2Online-1.3.jar`

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

1. **登录服（Lobby）**：`LoginQueue2-1.3.jar` 或 `LoginQueue2Limbo-1.3.jar`
2. **主服务器（Main）**：`LoginQueue2Online-1.3.jar`（用于 TPS 上报）

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

1. **登录服（Lobby）**：`LoginQueue2-1.3.jar` 或 `LoginQueue2Limbo-1.3.jar`
2. **BungeeCord/Velocity 代理**：`LoginQueue2BC-1.3.jar` 或 `LoginQueue2VC-1.3.jar`
3. **主服务器（Main）**：`LoginQueue2Online-1.3.jar`

配置 `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### Limbo 轻量级登录服模式

将 `LoginQueue2Limbo-1.3.jar` 放入 Limbo 服务器的 `plugins/` 文件夹。

**Limbo 模式特点**：
- 使用 Limbo 作为轻量级登录服，占用资源极低
- 支持 UDP 状态同步和 BungeeCord/Velocity 原生通道
- 信标点击和 `/join` 命令统一逻辑
- 功能与主插件完全一致，只是适配了 Limbo API

---

## 配置

### LoginQueue2 主插件

编辑 `plugins/LoginQueue2/config.yml`：

```yaml
# 插件语言（支持 zh_CN、zh_TW、en_US）
language: zh_CN

# 是否启用 BungeeCord 通道扩展（用于跨服转移）
enable-bungee-extension: true

# 是否启用调试日志（输出详细的插件运行信息）
debug: false

# UDP 服务器信息同步配置
udp-sync:
  # 是否启用 UDP 服务器信息同步
  enabled: false
  # 信息获取优先级: BC_CHANNEL 先使用 BungeeCord 消息通道, UDP 先使用 UDP
  priority: BC_CHANNEL
  # UDP 请求超时时间（毫秒）
  timeout: 3000
  # 预共享密钥（用于加密传输通信密钥，防止窃听）
  planned-key: "loginqueue"
  # 主服务器列表（支持多主服务器负载均衡）
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

# 登录队列配置
queue:
  # 主服务器在 BungeeCord 中的名称（单服务器模式使用）
  main-server: "main"
  # 主服务器最大可连接玩家数（单服务器模式使用）
  max-online: 50
  # 负载均衡策略: LEAST_PLAYERS 最少玩家, LEAST_LOAD 最低负载, ROUND_ROBIN 轮询, RANDOM 随机
  balance-strategy: LEAST_PLAYERS
  # 排序优先级（越靠前优先级越高）
  priority:
    - "permission:loginqueue.vip"
    - "permission:loginqueue.priority"
  # 默认优先级（数字越大越优先）
  default-priority: 0
  # 向 BungeeCord 刷新主服信息的周期（秒）
  refresh-interval: 5
  # 主服务器离线时的刷新周期（秒）
  offline-refresh-interval: 10
  # 主服务器连接阈值（0.0 - 1.0），超过此百分比时暂停放行新玩家
  threshold: 0.8
  # 是否限制排队中的玩家移动
  restrict-movement: false
  # 是否开启性能节省模式（禁用生物生成、时间流逝、天气更替）
  performance-mode: true
  # 玩家加入后是否自动进入排队队列
  auto-queue: false
  # 玩家加入后锁定等待的时间（秒），在此期间玩家无法交互
  lock-time: 3
  # 是否设置玩家加入后的游戏模式
  set-gamemode: true
  # 玩家加入后的游戏模式（SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR）
  gamemode: ADVENTURE
  # 是否允许玩家与方块交互（点击按钮、开门等）
  allow-block-interact: false
  # 是否允许玩家放置方块
  allow-block-place: false
  # 是否允许玩家破坏方块
  allow-block-break: false
  # 是否禁用下界维度（禁止玩家进入或传送到 NETHER）
  disable-nether: true
  # 是否禁用末地维度（禁止玩家进入或传送到 THE_END）
  disable-end: true
  # 是否禁用传送门（禁止玩家通过任何传送门进行传送）
  disable-portals: true
  # 管理员是否不受上述限制（拥有 loginqueue.admin.bypass 权限）
  admin-bypass: true
  # 是否限制玩家活动范围（超出范围拉回中心）
  restrict-range: false
  # 玩家活动范围限制（以出生点为中心，单位为方块）
  range-limit: 10
  # 登录点保护（禁止爆炸、取消玩家伤害、禁止 PVP）
  spawn-protection: true
  # 登录点保护区域半径（以出生点为中心，0 表示全地图保护）
  spawn-protection-radius: 0
  # 玩家出生点配置
  spawn:
    world: "world"
    x: 0.0
    y: -63.0
    z: 0.0
    pitch: 0.0
    yaw: 0.0
    radius: 0
  # 手动排队时发放的物品配置
  queue-item:
    slot: 4
    material: BEACON
    name: "&a加入游戏"
```

### LoginQueue2Online（子服务器）

编辑 `plugins/LoginQueue2Online/config.yml`：

```yaml
# 当前子服务器在代理端中的名称（必须与 LS2 配置中的名称一致）
server-name: "main"

# 向代理端广播服务器信息的周期（秒）
refresh-interval: 5
```

---

## 指令

### LoginQueue2 主插件（登录服）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/logseq skip [玩家名]` | `loginqueue.admin.skip` | 跳过排队，直接将玩家送入主服务器 |
| `/logseq list` | `loginqueue.admin.list` | 显示当前排队玩家列表 |
| `/logseq status` | `loginqueue.admin.status` | 显示主服务器状态（在线人数、负载等） |
| `/logseq refresh` | `loginqueue.admin.refresh` | 手动刷新主服务器状态缓存 |
| `/logseq reload` | `loginqueue.admin.reload` | 重载配置文件和语言文件 |
| `/logseq debug` | `loginqueue.admin.debug` | 切换调试模式（输出详细日志） |
| `/logseq info` | `loginqueue.admin.info` | 查看所有主服务器详细信息 |
| `/logseq help` | - | 显示帮助信息 |
| `/join` | - | 手动加入排队队列（非自动排队模式时使用） |

**指令别名**: `/ls` 是 `/logseq` 的别名

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

指令与主插件完全一致，支持 `/logseq` 和 `/ls` 别名。

### LoginQueue2Online（子服务器）

该插件无指令，启动后自动运行，通过 UDP 向登录服上报服务器状态信息。

---

## 权限节点

### LoginQueue2

| 权限 | 说明 |
|------|------|
| `loginqueue.admin.skip` | 允许使用 `/logseq skip` 跳过排队 |
| `loginqueue.admin.list` | 允许查看排队列表 |
| `loginqueue.admin.status` | 允许查看服务器状态 |
| `loginqueue.admin.refresh` | 允许手动刷新服务器状态 |
| `loginqueue.admin.reload` | 允许重载配置 |
| `loginqueue.admin.debug` | 允许切换调试模式 |
| `loginqueue.admin.info` | 允许查看服务器详细信息 |
| `loginqueue.admin.bypass` | 不受登录服限制（移动、交互等） |
| `loginqueue.vip` | VIP 排队优先级 |
| `loginqueue.priority` | 优先排队权限 |

---

## 各插件功能详解

### LoginQueue2（主插件）

**运行平台**: Spigot/Paper 1.13+
**安装位置**: 登录服（Lobby）
**必需性**: **必需**

**核心功能**:
- **登录队列管理**: 控制玩家进入主服务器的顺序，支持优先级排序
- **负载均衡**: 多主服务器环境下自动选择负载最低的服务器
- **玩家限制**: 限制排队中的玩家移动、交互、破坏方块等
- **维度保护**: 禁止玩家进入下界、末地，禁用传送门
- **出生点保护**: 禁止爆炸、取消伤害、禁止 PVP
- **性能节省模式**: 禁用生物生成、时间流逝、天气更替
- **UDP 状态同步**: 通过 UDP 直接获取子服务器状态，无需代理端插件
- **双模式支持**: 支持 BungeeCord 原生通道或自定义通道转移玩家
- **认证系统**: 内置可选的注册/登录/改密码功能

**负载均衡策略**:
- `LEAST_PLAYERS`: 选择在线人数最少的服务器
- `LEAST_LOAD`: 选择负载百分比最低的服务器
- `ROUND_ROBIN`: 轮询选择服务器
- `RANDOM`: 随机选择服务器

### LoginQueue2Limbo（Limbo 登录服插件）

**运行平台**: Limbo
**安装位置**: Limbo 服务器
**必需性**: 使用 Limbo 作为登录服时必需

**核心功能**: 功能与主插件完全一致，包括队列管理、负载均衡、玩家限制、UDP 同步、BungeeCord/Velocity 兼容、信标排队等。只是适配了 Limbo 平台 API。

### LoginQueue2Online（子服务器状态上报插件）

**运行平台**: Spigot/Paper 1.14+
**安装位置**: 各主服务器（Main）
**必需性**: 多主服务器环境下推荐安装，用于获取精确的 TPS 和在线状态

**核心功能**:
- 启动 UDP 服务端，监听登录服的查询请求
- 实时上报本服务器的在线人数、最大人数、在线状态、**TPS**
- 使用 AES 加密通信，支持预共享密钥
- 自动定期向登录服广播状态信息

**特点**: 无指令、无配置界面，配置后自动运行

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
| `UDP` | 直接 UDP 通信获取服务器状态 | UDP 优先模式（需要 Online 插件） |

---

## 依赖

- Java 8+（LoginQueue2VC 需要 Java 17）
- Maven 3.6+

---

## 许可证

MIT License
