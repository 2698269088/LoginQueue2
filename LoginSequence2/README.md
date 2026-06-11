# LoginSequence

> **警告：本插件仍处于开发阶段，功能尚未完善，不建议直接用于生产环境。**

LoginSequence 是一个 Minecraft Spigot 插件，用于管理大量玩家同时上线时的登录顺序，防止服务器因瞬时高并发连接而崩溃。

## 核心特性

- **登录队列管理**：玩家按优先级排队，有序进入主服务器
- **负载感知**：根据主服务器在线人数和阈值自动控制放行速度
- **BungeeCord 跨服支持**：通过自定义消息通道与 BungeeCord 通信
- **多语言支持**：简体中文、繁体中文、英语
- **登录点保护**：限制玩家活动范围，禁用维度传送，保护登录区域
- **性能节省模式**：可选禁用生物生成、时间流逝、天气更替
- **手动/自动排队**：支持玩家主动加入队列或自动进入排队

## 环境要求

- Spigot 1.13+
- Java 8+
- BungeeCord（跨服功能需要配合 BC 插件使用）

## 安装方式

1. 将编译好的 `LoginSequence-*.jar` 放入 Spigot 服务器的 `plugins/` 目录
2. 启动服务器，插件会自动生成默认配置文件
3. 按需修改 `plugins/LoginSequence/config.yml`
4. 重启服务器或使用 `/reload` 加载配置

## 主要命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/join` | 无 | 加入排队队列 |
| `/logseq skip [玩家]` | `loginsequence.admin.skip` | 允许指定玩家跳过排队 |
| `/logseq list` | `loginsequence.admin.list` | 查看当前排队玩家列表 |
| `/logseq status` | `loginsequence.admin.status` | 查看服务器状态 |
| `/logseq refresh` | `loginsequence.admin.refresh` | 手动刷新主服务器状态 |
| `/logseq help` | 无 | 查看帮助信息 |

## 配置说明

### 基础配置

```yaml
# 插件语言（支持 zh_CN、zh_TW、en_US）
language: zh_CN

queue:
  # 主服务器在 BungeeCord 中的名称
  main-server: "main"
  # 主服务器最大可连接玩家数
  max-online: 50
  # 连接阈值（0.0 - 1.0），超过此百分比时暂停放行新玩家
  threshold: 0.8
  # 向 BungeeCord 刷新主服信息的周期（秒）
  refresh-interval: 5
  # 主服务器离线时的刷新周期（秒）
  offline-refresh-interval: 10
```

### 玩家限制配置

```yaml
queue:
  # 是否限制排队中的玩家移动
  restrict-movement: false
  # 是否限制玩家活动范围（超出范围拉回中心）
  restrict-range: false
  range-limit: 10
  # 登录点保护（禁止爆炸、取消玩家伤害、禁止 PVP）
  spawn-protection: true
  spawn-protection-radius: 0
  # 是否禁用下界/末地维度和传送门
  disable-nether: true
  disable-end: true
  disable-portals: true
  # 玩家加入后锁定等待的时间（秒）
  lock-time: 3
  # 玩家加入后的游戏模式
  set-gamemode: true
  gamemode: ADVENTURE
```

### 优先级配置

```yaml
queue:
  priority:
    - "permission:loginsequence.vip"
    - "permission:loginsequence.priority"
    - "name:Player1"
  default-priority: 0
```

支持 `permission:权限节点` 和 `name:玩家名` 两种匹配方式，越靠前优先级越高。

## BungeeCord 消息通道

本插件通过自定义 BungeeCord 消息通道与 BC 端通信，需要配合 BC 插件使用。

### 通道列表

| 通道名 | 方向 | 功能 |
|--------|------|------|
| `LoginSequence:ConnectOther` | Spigot → BC | 通知 BC 将指定玩家转移到目标服务器 |
| `LoginSequence:ServerInfo` | Spigot ↔ BC | 请求/返回指定服务器的状态信息 |

### `LoginSequence:ConnectOther`

**数据格式（Spigot 发送）：**
```
[targetPlayerName]  (String)
[targetServerName]  (String)
```

**BC 端处理：** 读取玩家名和服务器名，通过 BungeeCord API 执行跨服传送。

### `LoginSequence:ServerInfo`

**请求格式（Spigot 发送）：**
```
[serverName]  (String)
```

**响应格式（BC 返回）：**
```
[serverName]   (String)
[onlineCount]  (int)
[maxPlayers]   (int)
[isOnline]     (boolean)
```

**BC 端处理：** 查询指定服务器的在线人数、最大玩家数、在线状态，通过同一通道返回。

## 权限节点

| 权限 | 说明 | 默认 |
|------|------|------|
| `loginsequence.admin.skip` | 允许跳过排队 | OP |
| `loginsequence.admin.list` | 允许查看排队列表 | OP |
| `loginsequence.admin.status` | 允许查看服务器状态 | OP |
| `loginsequence.admin.refresh` | 允许手动刷新服务器状态 | OP |
| `loginsequence.admin.bypass` | 不受登录服限制 | OP |

## 注意事项

- 本插件仍在积极开发中，可能存在未发现的 Bug
- 升级版本前请备份配置文件
- 跨服功能需要配合 BungeeCord 插件使用，单独使用 Spigot 插件无法实现跨服传送
- 生产环境使用前建议充分测试
