# LoginSequence2

LoginSequence2 是一个 Minecraft 服务器登录队列系统，支持 Spigot/Paper 子服务器、BungeeCord、Velocity 代理端和 Limbo 轻量级登录服。通过 Maven 多模块聚合构建，统一管理五个配套插件。
本插件组是为了替代旧版的LoginSequence插件，经过彻底重构，代码质量和效率明显好过旧版。

> **LoginSequence2** 是新一代 Minecraft 登录队列解决方案，为群组服提供高性能的玩家排队、负载均衡和跨服转移功能。支持 Spigot/Paper、BungeeCord、Velocity 和 Limbo 多种平台组合，满足不同规模服务器的部署需求。

## 项目结构

```
LoginSequence/
├── pom.xml                          # 根聚合 POM
├── LoginSequence2/                  # 主插件（Spigot/Paper）
│   └── src/main/java/top/mcocet/loginsequence2/
├── LoginSequence2BC/                # BungeeCord 代理端配套插件
│   └── src/main/java/top/mcocet/loginsequence2bc/
├── LoginSequence2Online/            # 子服务器在线状态上报插件
│   └── src/main/java/top/mcocet/loginsequence2online/
├── LoginSequence2VC/                # Velocity 代理端配套插件
│   └── src/main/java/top/mcocet/loginsequence2vc/
└── LoginSequence2Limbo/             # Limbo 登录服配套插件
    └── src/main/java/top/mcocet/loginsequence2limbo/
```

## 模块说明

| 模块 | 平台 | 作用 |
|------|------|------|
| LoginSequence2 | Spigot/Paper 1.13+ | 主插件，提供登录队列、玩家限制、指令等功能 |
| LoginSequence2BC | BungeeCord | 代理端插件，处理跨服转移和服务器信息查询 |
| LoginSequence2Online | Spigot/Paper 1.14+ | 子服务器插件，上报本服务器在线状态 |
| LoginSequence2VC | Velocity 3.x | Velocity 代理端插件，功能同 LS2BC |
| LoginSequence2Limbo | Limbo | 轻量级登录服插件，提供排队功能和状态同步 |

## 构建

```bash
mvn clean package
```

构建完成后，所有 jar 文件会自动复制到项目根目录的 `dist/` 文件夹：

```
dist/
├── LoginSequence2-1.0.1.jar
├── LoginSequence2BC-1.0.1.jar
├── LoginSequence2Online-1.0.1.jar
├── LoginSequence2VC-1.0.1.jar
└── LoginSequence2Limbo-1.0.1.jar
```

## 安装

### 基础安装（单服务器）

将 `LoginSequence2-1.0.1.jar` 放入登录服（Lobby）的 `plugins/` 文件夹。

### 基础安装（Limbo 登录服）

将 `LoginSequence2Limbo-1.0.1.jar` 放入 Limbo 服务器的 `plugins/` 文件夹。

**Limbo 模式特点**：
- 使用 Limbo 作为轻量级登录服，占用资源极低
- 支持 UDP 状态同步和 BungeeCord/Velocity 原生通道
- 信标点击和 `/join` 命令统一逻辑

### 群组服安装（BungeeCord + UDP 优先模式 - 推荐）

**无需安装 BC 插件**，使用 UDP 直接同步状态 + BungeeCord 原生通道转移玩家。

1. **登录服（Lobby）**：放入 `LoginSequence2-1.0.1.jar`（Spigot/Paper）或 `LoginSequence2Limbo-1.0.1.jar`（Limbo）
2. **主服务器（Main1, Main2...）**：放入 `LoginSequence2Online-1.0.1.jar`

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

### 群组服安装（BungeeCord + BC 优先模式）

**需要安装 BC 插件**，使用 BungeeCord 自定义通道通信。

1. **登录服（Lobby）**：放入 `LoginSequence2-1.0.1.jar`（Spigot/Paper）或 `LoginSequence2Limbo-1.0.1.jar`（Limbo）
2. **BungeeCord 代理**：放入 `LoginSequence2BC-1.0.1.jar`
3. **主服务器（Main）**：放入 `LoginSequence2Online-1.0.1.jar`

配置 `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

### 群组服安装（Velocity + BC 优先模式）

1. **登录服（Lobby）**：放入 `LoginSequence2-1.0.1.jar`（Spigot/Paper）或 `LoginSequence2Limbo-1.0.1.jar`（Limbo）
2. **Velocity 代理**：放入 `LoginSequence2VC-1.0.1.jar`
3. **主服务器（Main）**：放入 `LoginSequence2Online-1.0.1.jar`

配置 `config.yml`:
```yaml
enable-bungee-extension: true
udp-sync:
  enabled: true
  priority: BC_CHANNEL
```

## 配置

### LoginSequence2 主插件

编辑 `plugins/LoginSequence/config.yml`：

```yaml
# 插件语言（支持 zh_CN、zh_TW、en_US）
language: zh_CN

# 是否启用 BungeeCord 通道扩展（需要配合代理端插件使用）
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
  planned-key: "loginsequence"
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
    - "permission:loginsequence.vip"
    - "permission:loginsequence.priority"
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
  # 管理员是否不受上述限制（拥有 loginsequence.admin.bypass 权限）
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

### LoginSequence2Online（子服务器）

编辑 `plugins/LoginSequence2Online/config.yml`：

```yaml
# 当前子服务器在代理端中的名称（必须与 LS2 配置中的名称一致）
server-name: "main"

# 向代理端广播服务器信息的周期（秒）
refresh-interval: 5
```

## 指令

### LoginSequence2 主插件（登录服）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/logseq skip [玩家名]` | `loginsequence.admin.skip` | 跳过排队，直接将玩家送入主服务器 |
| `/logseq list` | `loginsequence.admin.list` | 显示当前排队玩家列表 |
| `/logseq status` | `loginsequence.admin.status` | 显示主服务器状态（在线人数、负载等） |
| `/logseq refresh` | `loginsequence.admin.refresh` | 手动刷新主服务器状态缓存 |
| `/logseq reload` | `loginsequence.admin.reload` | 重载配置文件和语言文件 |
| `/logseq debug` | `loginsequence.admin.debug` | 切换调试模式（输出详细日志） |
| `/logseq info` | `loginsequence.admin.info` | 查看所有主服务器详细信息 |
| `/logseq help` | - | 显示帮助信息 |
| `/join` | - | 手动加入排队队列（非自动排队模式时使用） |

**指令别名**: `/ls` 是 `/logseq` 的别名

### LoginSequence2BC（BungeeCord 代理端）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/lsbc reload` | `loginsequence2bc.admin` | 重载配置文件 |
| `/lsbc debug` | `loginsequence2bc.admin` | 切换调试模式 |
| `/lsbc help` | `loginsequence2bc.admin` | 显示帮助信息 |

**指令别名**: `/loginsequencebc`

### LoginSequence2VC（Velocity 代理端）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/lsvc reload` | `loginsequence2vc.admin` | 重载配置文件 |
| `/lsvc debug` | `loginsequence2vc.admin` | 切换调试模式 |
| `/lsvc help` | `loginsequence2vc.admin` | 显示帮助信息 |

**指令别名**: `/loginsequencevc`

### LoginSequence2Limbo（Limbo 登录服）

| 指令 | 权限 | 说明 |
|------|------|------|
| `/logseq skip [玩家名]` | `loginsequence.admin.skip` | 跳过排队，直接将玩家送入主服务器 |
| `/logseq list` | `loginsequence.admin.list` | 显示当前排队玩家列表 |
| `/logseq status` | `loginsequence.admin.status` | 显示主服务器状态 |
| `/logseq refresh` | `loginsequence.admin.refresh` | 手动刷新主服务器状态缓存 |
| `/logseq reload` | `loginsequence.admin.reload` | 重载配置文件和语言文件 |
| `/logseq debug` | `loginsequence.admin.debug` | 切换调试模式 |
| `/logseq info` | `loginsequence.admin.info` | 查看所有主服务器详细信息 |
| `/logseq help` | - | 显示帮助信息 |
| `/join` | - | 手动加入排队队列 |

**指令别名**: `/ls` 是 `/logseq` 的别名

### LoginSequence2Online（子服务器）

该插件无指令，启动后自动运行，通过 UDP 向登录服上报服务器状态信息。

## 权限节点

### LoginSequence2

| 权限 | 说明 |
|------|------|
| `loginsequence.admin.skip` | 允许使用 `/logseq skip` 跳过排队 |
| `loginsequence.admin.list` | 允许查看排队列表 |
| `loginsequence.admin.status` | 允许查看服务器状态 |
| `loginsequence.admin.refresh` | 允许手动刷新服务器状态 |
| `loginsequence.admin.reload` | 允许重载配置 |
| `loginsequence.admin.debug` | 允许切换调试模式 |
| `loginsequence.admin.info` | 允许查看服务器详细信息 |
| `loginsequence.admin.bypass` | 不受登录服限制（移动、交互等） |
| `loginsequence.vip` | VIP 排队优先级 |
| `loginsequence.priority` | 优先排队权限 |

## 各插件功能详解

### LoginSequence2（主插件）

**运行平台**: Spigot/Paper 1.13+
**安装位置**: 登录服（Lobby）

**核心功能**:
- **登录队列管理**: 控制玩家进入主服务器的顺序，支持优先级排序
- **负载均衡**: 多主服务器环境下自动选择负载最低的服务器
- **玩家限制**: 限制排队中的玩家移动、交互、破坏方块等
- **维度保护**: 禁止玩家进入下界、末地，禁用传送门
- **出生点保护**: 禁止爆炸、取消伤害、禁止 PVP
- **性能节省模式**: 禁用生物生成、时间流逝、天气更替
- **UDP 状态同步**: 通过 UDP 直接获取子服务器状态，无需代理端插件
- **双模式支持**: 支持 BungeeCord 原生通道或自定义通道转移玩家

**负载均衡策略**:
- `LEAST_PLAYERS`: 选择在线人数最少的服务器
- `LEAST_LOAD`: 选择负载百分比最低的服务器
- `ROUND_ROBIN`: 轮询选择服务器
- `RANDOM`: 随机选择服务器

### LoginSequence2BC（BungeeCord 代理端插件）

**运行平台**: BungeeCord
**安装位置**: BungeeCord 代理端

**核心功能**:
- 监听自定义插件消息通道
- 处理玩家跨服转移请求（`ConnectOther` / `ConnectRequest`）
- 处理服务器信息查询请求（`ServerInfo`）
- 将子服务器状态信息转发给登录服

**适用场景**: 使用 BC 通道优先模式时需要安装

### LoginSequence2VC（Velocity 代理端插件）

**运行平台**: Velocity 3.x
**安装位置**: Velocity 代理端

**核心功能**: 功能同 LS2BC，适配 Velocity 平台

**适用场景**: 使用 Velocity 代理且 BC 通道优先模式时需要安装

### LoginSequence2Limbo（Limbo 登录服插件）

**运行平台**: Limbo
**安装位置**: Limbo 服务器

**核心功能**:
- **登录队列管理**: 控制玩家进入主服务器的顺序，支持优先级排序
- **负载均衡**: 多主服务器环境下自动选择负载最低的服务器
- **玩家限制**: 限制排队中的玩家移动、交互
- **出生点保护**: 禁止爆炸、取消伤害
- **UDP 状态同步**: 通过 UDP 直接获取子服务器状态
- **BungeeCord/Velocity 兼容**: 支持两种代理的原生通道转移玩家
- **信标排队**: 玩家点击信标物品即可加入排队队列

**适用场景**: 使用 Limbo 作为轻量级登录服时需要安装

### LoginSequence2Online（子服务器状态上报插件）

**运行平台**: Spigot/Paper 1.14+
**安装位置**: 各主服务器（Main）

**核心功能**:
- 启动 UDP 服务端，监听登录服的查询请求
- 实时上报本服务器的在线人数、最大人数、在线状态
- 使用 AES 加密通信，支持预共享密钥
- 自动定期向登录服广播状态信息

**特点**: 无指令、无配置界面，配置后自动运行

## 消息通道

| 通道 | 说明 | 使用场景 |
|------|------|----------|
| `BungeeCord` | BungeeCord 原生通道，用于直接转移玩家 | UDP 优先模式 |
| `loginsequence:connectother` | 通知代理端将指定玩家转移到目标服务器 | BC 优先模式 |
| `loginsequence:connectrequest` | 玩家主动请求连接到目标服务器 | BC 优先模式 |
| `loginsequence:serverinfo` | 查询/上报服务器状态信息 | BC 优先模式 |

## 依赖

- Java 8+（LoginSequence2VC 需要 Java 17）
- Maven 3.6+

## 许可证

MIT License
