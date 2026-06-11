# LoginSequence2

LoginSequence2 是一个 Minecraft 服务器登录队列系统，支持 Spigot/Paper 子服务器、BungeeCord 和 Velocity 代理端。通过 Maven 多模块聚合构建，统一管理四个配套插件。
本插件组是为了替代旧版的LoginSequence插件，经过彻底重构，代码质量和效率明显好过旧版。

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
└── LoginSequence2VC/                # Velocity 代理端配套插件
    └── src/main/java/top/mcocet/loginsequence2vc/
```

## 模块说明

| 模块 | 平台 | 作用 |
|------|------|------|
| LoginSequence2 | Spigot/Paper 1.13+ | 主插件，提供登录队列、玩家限制、指令等功能 |
| LoginSequence2BC | BungeeCord | 代理端插件，处理跨服转移和服务器信息查询 |
| LoginSequence2Online | Spigot/Paper 1.14+ | 子服务器插件，上报本服务器在线状态 |
| LoginSequence2VC | Velocity 3.x | Velocity 代理端插件，功能同 LS2BC |

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
└── LoginSequence2VC-1.0.1.jar
```

## 安装

### 基础安装（单服务器）

将 `LoginSequence2-1.0.1.jar` 放入登录服（Lobby）的 `plugins/` 文件夹。

### 群组服安装（BungeeCord）

1. **登录服（Lobby）**：放入 `LoginSequence2-1.0.1.jar`
2. **BungeeCord 代理**：放入 `LoginSequence2BC-1.0.1.jar`
3. **主服务器（Main）**：放入 `LoginSequence2Online-1.0.1.jar`

### 群组服安装（Velocity）

1. **登录服（Lobby）**：放入 `LoginSequence2-1.0.1.jar`
2. **Velocity 代理**：放入 `LoginSequence2VC-1.0.1.jar`
3. **主服务器（Main）**：放入 `LoginSequence2Online-1.0.1.jar`

## 配置

### LoginSequence2 主插件

编辑 `plugins/LoginSequence/config.yml`：

```yaml
# 插件语言（支持 zh_CN、zh_TW、en_US）
language: zh_CN

# 是否启用 BungeeCord 通道扩展（需要配合代理端插件使用）
enable-bungee-extension: true

# 登录队列配置
queue:
  # 主服务器在代理端中的名称
  main-server: "main"
  # 主服务器最大可连接玩家数
  max-online: 50
  # 排序优先级
  priority:
    - "permission:loginsequence.vip"
    - "permission:loginsequence.priority"
  # 默认优先级（数字越大越优先）
  default-priority: 0
  # 刷新周期（秒）
  refresh-interval: 5
  # 连接阈值（0.0 - 1.0），超过时暂停放行
  threshold: 0.8
  # 是否限制排队中的玩家移动
  restrict-movement: false
  # 性能节省模式
  performance-mode: true
  # 玩家加入后是否自动进入排队队列
  auto-queue: false
  # 手动排队时发放的物品配置
  queue-item:
    slot: 4
    material: BEACON
    name: "&a加入游戏"
```

### LoginSequence2Online

编辑 `plugins/LoginSequence2Online/config.yml`：

```yaml
# 当前子服务器在代理端中的名称
server-name: "main"

# 向代理端广播服务器信息的周期（秒）
refresh-interval: 5
```

## 指令

| 指令 | 权限 | 说明 |
|------|------|------|
| `/logseq skip [玩家名]` | `loginsequence.admin.skip` | 跳过排队，直接进入服务器 |
| `/logseq list` | `loginsequence.admin.list` | 显示当前排队玩家列表 |
| `/logseq status` | `loginsequence.admin.status` | 显示服务器状态 |
| `/logseq refresh` | `loginsequence.admin.refresh` | 手动刷新主服务器状态 |
| `/logseq reload` | `loginsequence.admin.reload` | 重载配置文件 |
| `/logseq info` | `loginsequence.admin.info` | 查看主服务器详细信息 |
| `/logseq help` | - | 显示帮助 |
| `/join` | - | 加入排队队列（手动模式） |

## 消息通道

| 通道 | 说明 |
|------|------|
| `LoginSequence:ConnectOther` | 通知代理端将指定玩家转移到目标服务器 |
| `LoginSequence:ConnectRequest` | 玩家主动请求连接到目标服务器 |
| `LoginSequence:ServerInfo` | 查询/上报服务器状态信息 |

## 依赖

- Java 8+（LoginSequence2VC 需要 Java 17）
- Maven 3.6+

## 许可证

Copyright (c) MCOCET. All rights reserved.
