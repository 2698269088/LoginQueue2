package top.mcocet.loginqueue2limbo.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.player.Player;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.udp.UDPClient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class BungeeMessenger implements Listener {

    /** 自定义消息通道：用于通知代理端将指定玩家转移到目标服务器 */
    public static final String CHANNEL_CONNECT_OTHER = "loginqueue2:connectother";
    /** 自定义消息通道：用于玩家主动请求连接到目标服务器 */
    public static final String CHANNEL_CONNECT_REQUEST = "loginqueue2:connectrequest";
    /** 自定义消息通道：用于获取指定服务器的状态信息 */
    public static final String CHANNEL_SERVER_INFO = "loginqueue2:serverinfo";
    /** 自定义消息通道：用于通知代理端玩家登录成功 */
    public static final String CHANNEL_LOGIN_SUCCESS = "loginqueue2:loginsuccess";
    /** BungeeCord 原生通道（Bukkit 内置，无需注册） */
    public static final String CHANNEL_BUNGEE_CORD = "BungeeCord";

    private final LoginQueue2Limbo plugin;
    private final String mainServer;
    private final boolean enabled;

    // 缓存服务器状态信息（最大玩家数等）
    private final ConcurrentHashMap<String, ServerStatus> serverStatusCache = new ConcurrentHashMap<>();
    // 记录每个服务器最后一次收到响应的时间戳
    private final ConcurrentHashMap<String, Long> lastServerInfoTimeMap = new ConcurrentHashMap<>();
    // 子服务器信息请求超时时间（毫秒）
    private static final long SERVER_INFO_TIMEOUT = 10000L;

    // UDP 客户端列表（支持多主服务器）
    private final List<UDPClient> udpClients = new ArrayList<>();
    private final boolean udpEnabled;
    private final String udpPriority;

    // 轮询索引（用于 ROUND_ROBIN 策略）
    private final java.util.concurrent.atomic.AtomicInteger roundRobinIndex = new java.util.concurrent.atomic.AtomicInteger(0);

    public BungeeMessenger(LoginQueue2Limbo plugin) {
        this(plugin, true);
    }

    public BungeeMessenger(LoginQueue2Limbo plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.mainServer = plugin.getConfigValueString("queue.main-server", "main");
        this.udpEnabled = plugin.getConfigValueBoolean("udp-sync.enabled", false);
        this.udpPriority = plugin.getConfigValueString("udp-sync.priority", "BC_CHANNEL");

        // 初始化 UDP 客户端（支持多主服务器）
        // UDP 初始化独立于 BC 扩展开关，确保 UDP 同步可以单独工作
        if (udpEnabled) {
            initUDPClients();
        }
    }

    private boolean isDebug() {
        return plugin.isDebug();
    }

    private void initUDPClients() {
        String plannedKey = plugin.getConfigValueString("udp-sync.planned-key", "");
        int udpTimeout = plugin.getConfigValueInt("udp-sync.timeout", 3000);

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 初始化开始，planned-key: " + (plannedKey != null && !plannedKey.isEmpty() ? "已配置" : "未配置") + "，超时: " + udpTimeout + "ms");
        }

        // 多服务器模式 - 支持 YAML 列表格式
        java.util.List<Map<?, ?>> serversList = plugin.getConfigValueMapList("udp-sync.servers");
        if (serversList != null && !serversList.isEmpty()) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 多服务器模式，配置数量: " + serversList.size());
            }
            int index = 0;
            for (Map<?, ?> serverMap : serversList) {
                index++;
                Object nameObj = serverMap.get("name");
                String serverName = nameObj != null ? String.valueOf(nameObj) : "main" + index;
                Object hostObj = serverMap.get("host");
                String host = hostObj != null ? String.valueOf(hostObj) : "127.0.0.1";
                int port = parseInt(serverMap.get("port"), 25566);
                int gamePort = parseInt(serverMap.get("game-port"), -1);
                Object keyObj = serverMap.get("secret-key");
                String secretKey = keyObj != null ? String.valueOf(keyObj) : "";

                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 正在初始化客户端 [" + serverName + "] -> " + host + ":" + port + (gamePort > 0 ? " (MSLP端口:" + gamePort + ")" : ""));
                }
                UDPClient client = new UDPClient(plugin, serverName, host, port, gamePort, udpTimeout, secretKey, plannedKey);
                if (client.init()) {
                    udpClients.add(client);
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 同步已配置 [" + serverName + "]: " + host + ":" + port);
                } else {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 客户端 [" + serverName + "] 初始化失败，已跳过。");
                }
            }
        }

        // 兼容旧配置（单服务器模式）
        if (udpClients.isEmpty()) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 单服务器模式（兼容旧配置）");
            }
            String udpHost = plugin.getConfigValueString("udp-sync.host", "127.0.0.1");
            int udpPort = plugin.getConfigValueInt("udp-sync.port", 25566);
            String secretKey = plugin.getConfigValueString("udp-sync.secret-key", "");
            UDPClient client = new UDPClient(plugin, mainServer, udpHost, udpPort, udpTimeout, secretKey, plannedKey);
            if (client.init()) {
                udpClients.add(client);
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 同步已配置（单服务器模式）: " + udpHost + ":" + udpPort);
            } else {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 客户端（单服务器模式）初始化失败。");
            }
        }

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 初始化完成，成功客户端数: " + udpClients.size());
        }
    }

    public void shutdown() {
        // 关闭所有 UDP 客户端
        for (UDPClient client : udpClients) {
            client.shutdown();
        }
        udpClients.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 刷新所有主服务器的信息
     */
    public void refresh() {
        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 开始刷新服务器状态，UDP启用=" + udpEnabled + "，优先级=" + udpPriority);
        }

        // 记录刷新前的时间戳，用于判断本次刷新是否收到新响应
        Map<String, Long> beforeRefreshTimes = new java.util.HashMap<>();
        for (String server : serverStatusCache.keySet()) {
            beforeRefreshTimes.put(server, lastServerInfoTimeMap.getOrDefault(server, 0L));
        }

        if (udpEnabled && "MSLP".equalsIgnoreCase(udpPriority)) {
            // MSLP 优先模式
            boolean anySuccess = tryRefreshViaMSLP();
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 刷新结果: " + (anySuccess ? "成功" : "失败"));
            }
            if (!anySuccess) {
                if (plugin.isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 获取失败，回退到 UDP");
                }
                anySuccess = tryRefreshViaUDP();
                if (!anySuccess && enabled) {
                    if (plugin.isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 获取失败，回退到 BungeeCord 通道");
                    }
                    tryRefreshViaBC();
                }
            }
        } else if (udpEnabled && "UDP".equalsIgnoreCase(udpPriority)) {
            // 优先使用 UDP
            boolean anySuccess = tryRefreshViaUDP();
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 刷新结果: " + (anySuccess ? "成功" : "失败"));
            }
            if (!anySuccess) {
                // UDP 失败，回退到 BC 通道
                if (plugin.isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 获取失败，回退到 BungeeCord 通道");
                }
                tryRefreshViaBC();
            }
        } else {
            // 优先使用 BC 通道
            boolean bcSuccess = tryRefreshViaBC();
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] BC 通道刷新结果: " + (bcSuccess ? "成功" : "失败"));
            }
            if (!bcSuccess && udpEnabled) {
                // BC 通道失败，回退到 UDP
                if (plugin.isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] BungeeCord 通道获取失败，回退到 UDP");
                }
                tryRefreshViaUDP();
            }
        }

        // 清理过期缓存：对于本次刷新没有收到新响应的服务器，标记为离线
        long now = System.currentTimeMillis();
        for (String server : new java.util.ArrayList<>(serverStatusCache.keySet())) {
            Long lastTime = lastServerInfoTimeMap.get(server);
            Long beforeTime = beforeRefreshTimes.get(server);
            // 如果该服务器在刷新前有时间戳，且刷新后时间戳没有变化（没有收到新响应），且已超时
            if (lastTime != null && beforeTime != null && lastTime.equals(beforeTime)
                    && (now - lastTime) >= SERVER_INFO_TIMEOUT) {
                ServerStatus oldStatus = serverStatusCache.get(server);
                if (oldStatus != null && oldStatus.isOnline()) {
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 服务器 " + server + " 的缓存已过期（" + ((now - lastTime) / 1000) + "秒未更新），标记为离线");
                    }
                    serverStatusCache.put(server, new ServerStatus(server, oldStatus.getOnlinePlayers(), oldStatus.getMaxPlayers(), false));
                }
            }
        }

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 刷新完成，当前缓存服务器数: " + serverStatusCache.size());
        }
    }

    /**
     * 尝试通过 Minecraft Server List Ping 获取所有服务器信息
     * 对于配置了 game-port 的服务器直接 MSLP
     * 对于未配置 game-port 的服务器，通过 BungeeCord ServerIP 获取端口后再 MSLP
     *
     * @return 是否至少有一个直接成功（配置了 game-port 的）
     */
    private boolean tryRefreshViaMSLP() {
        boolean anyDirectSuccess = false;
        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 通过 MSLP 刷新，客户端数量: " + udpClients.size());
        }
        for (UDPClient client : udpClients) {
            int gamePort = client.getGamePort();
            if (gamePort > 0) {
                // 配置了 game-port，直接执行 MSLP
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 直接请求服务器信息: [" + client.getServerName() + "] 端口=" + gamePort);
                }
                ServerStatus status = client.requestServerInfoViaMSLP(null, gamePort);
                if (status != null) {
                    serverStatusCache.put(status.getServerName(), status);
                    lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                    anyDirectSuccess = true;
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 直接获取成功: " + status);
                    }
                } else {
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 直接获取失败: [" + client.getServerName() + "]");
                    }
                }
            } else {
                // 未配置 game-port，通过 BungeeCord ServerIP 获取端口
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 未配置 game-port，通过 BungeeCord ServerIP 获取: [" + client.getServerName() + "]");
                }
                requestServerIPViaBC(client.getServerName());
            }
        }
        return anyDirectSuccess;
    }

    /**
     * 通过 BungeeCord 原生 ServerIP 请求获取服务器地址
     */
    private void requestServerIPViaBC(String server) {
        Player player = getAnyOnlinePlayer();
        if (player == null) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("ServerIP");
        out.writeUTF(server);
        try {
            player.sendPluginMessage(CHANNEL_BUNGEE_CORD, out.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 尝试通过 UDP 获取所有服务器信息
     *
     * @return 是否至少有一个成功
     */
    private boolean tryRefreshViaUDP() {
        boolean anySuccess = false;
        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 通过 UDP 刷新，客户端数量: " + udpClients.size());
        }
        for (UDPClient client : udpClients) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 请求服务器信息: [" + client.getServerName() + "]");
            }
            ServerStatus status = client.requestServerInfo();
            if (status != null) {
                serverStatusCache.put(status.getServerName(), status);
                lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                anySuccess = true;
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 获取成功: " + status);
                }
            } else {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] UDP 获取失败: [" + client.getServerName() + "]");
                }
            }
        }
        return anySuccess;
    }

    /**
     * 尝试通过 BungeeCord 通道获取所有已配置服务器的信息
     *
     * @return 是否至少有一个服务器在刷新前已有缓存（用于判断是否可能获取到状态）
     */
    private boolean tryRefreshViaBC() {
        // 收集所有需要请求的服务器名称
        List<String> serversToRequest = new ArrayList<>();
        serversToRequest.add(mainServer);
        for (UDPClient client : udpClients) {
            String name = client.getServerName();
            if (!serversToRequest.contains(name)) {
                serversToRequest.add(name);
            }
        }

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 需要请求的服务器=" + serversToRequest + ", enabled=" + enabled);
        }

        Player player = getAnyOnlinePlayer();
        if (player == null) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 没有在线玩家，无法发送请求");
            }
            return !serverStatusCache.isEmpty();
        }

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 使用玩家=" + player.getName() + " 发送请求");
        }

        if (enabled) {
            // 开启BC扩展时，使用自定义通道
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 使用自定义通道 loginqueue2:serverinfo 发送请求");
            }
            for (String server : serversToRequest) {
                requestServerInfo(server);
            }
        } else {
            // 关闭BC扩展时，使用BungeeCord原生ServerIP + Minecraft Server List Ping
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 关闭BC扩展模式，使用BungeeCord原生ServerIP检测服务器状态");
            }
            for (String server : serversToRequest) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ServerIP");
                out.writeUTF(server);
                try {
                    player.sendPluginMessage(CHANNEL_BUNGEE_CORD, out.toByteArray());
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 已发送ServerIP请求，目标服务器=" + server);
                    }
                } catch (IOException e) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 发送ServerIP请求失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // 判断依据：是否有在线玩家可以发送请求，且缓存中已有数据
        // 注意：这不代表本次请求一定成功，只是表示通道可能可用
        boolean result = !serverStatusCache.isEmpty();
        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] tryRefreshViaBC: 返回结果=" + result + ", 缓存大小=" + serverStatusCache.size());
        }
        return result;
    }

    /**
     * 请求指定服务器的状态信息（最大玩家数、在线人数等）
     *
     * @param server 目标服务器名
     */
    public void requestServerInfo(String server) {
        if (!enabled) return;

        Player player = getAnyOnlinePlayer();
        if (player == null) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("REQ");
        out.writeUTF(server);
        try {
            player.sendPluginMessage(CHANNEL_SERVER_INFO, out.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 请求主服务器的状态信息
     */
    public void requestMainServerInfo() {
        requestServerInfo(mainServer);
    }

    /**
     * 获取所有已知子服务器的状态信息
     */
    public ConcurrentHashMap<String, ServerStatus> getAllServerStatus() {
        return new ConcurrentHashMap<>(serverStatusCache);
    }

    /**
     * 判断指定服务器是否在线（基于缓存数据）
     */
    public boolean isServerOnline(String server) {
        ServerStatus status = serverStatusCache.get(server);
        Long lastTime = lastServerInfoTimeMap.get(server);
        return status != null && status.isOnline()
                && lastTime != null
                && (System.currentTimeMillis() - lastTime) < SERVER_INFO_TIMEOUT;
    }

    /**
     * 将玩家连接到指定子服务器
     * UDP 优先模式下使用 BungeeCord 原生通道（无需 BC 插件）
     * 默认模式下使用自定义通道（需要 BC 插件）
     */
    public void connectPlayerToServer(Player player, String server) {
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (udpPreferred) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 发送连接请求(BungeeCord原生): 玩家=" + player.getName() + " 目标服务器=" + server);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            try {
                player.sendPluginMessage(CHANNEL_BUNGEE_CORD, out.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (enabled) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 发送连接请求(自定义通道): 玩家=" + player.getName() + " 目标服务器=" + server);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(server);
            try {
                player.sendPluginMessage(CHANNEL_CONNECT_REQUEST, out.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            // 关闭BC扩展时，使用BungeeCord原生Connect通道
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 发送连接请求(BungeeCord原生): 玩家=" + player.getName() + " 目标服务器=" + server);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            try {
                player.sendPluginMessage(CHANNEL_BUNGEE_CORD, out.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 通过自定义通道通知代理端将指定玩家转移到目标服务器
     * 用于转移其他玩家（非当前玩家自己）
     *
     * @param targetPlayer 要转移的玩家名
     * @param targetServer 目标服务器名
     */
    public void connectOtherPlayer(String targetPlayer, String targetServer) {
        if (!enabled) return;

        Player player = getAnyOnlinePlayer();
        if (player == null) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(targetPlayer);
        out.writeUTF(targetServer);
        try {
            player.sendPluginMessage(CHANNEL_CONNECT_OTHER, out.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取缓存中的服务器状态信息
     *
     * @param server 服务器名
     * @return 服务器状态，若无缓存返回 null
     */
    public ServerStatus getServerStatus(String server) {
        return serverStatusCache.get(server);
    }

    /**
     * 获取主服务器的缓存状态信息
     */
    public ServerStatus getMainServerStatus() {
        return serverStatusCache.get(mainServer);
    }

    /**
     * 将玩家连接到主服务器
     */
    public void connectToMainServer(Player player) {
        connectPlayerToServer(player, mainServer);
    }

    public int getMainServerPlayerCount() {
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (!udpPreferred) {
            ServerStatus status = getMainServerStatus();
            return status != null ? status.getOnlinePlayers() : -1;
        }

        // UDP 优先模式下，返回所有在线 UDP 服务器的总在线人数
        int totalOnline = 0;
        for (ServerStatus status : getOnlineMainServers()) {
            totalOnline += status.getOnlinePlayers();
        }
        return totalOnline;
    }

    /**
     * 判断主服务器是否在线（基于缓存数据）
     * UDP 优先模式下，检查是否有任何 UDP 配置的服务器在线
     */
    public boolean isMainServerOnline() {
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (!udpPreferred) {
            return isServerOnline(mainServer);
        }

        // UDP 优先模式下，检查是否有任何 UDP 配置的服务器在线
        java.util.List<String> udpServerNames = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> map : plugin.getConfigValueMapList("udp-sync.servers")) {
            Object nameObj = map.get("name");
            if (nameObj != null) {
                udpServerNames.add(String.valueOf(nameObj));
            }
        }
        // 兼容旧配置（单服务器模式）
        if (udpServerNames.isEmpty() && plugin.getConfigValueString("udp-sync.host", null) != null) {
            udpServerNames.add(plugin.getConfigValueString("queue.main-server", "main"));
        }

        for (String serverName : udpServerNames) {
            if (isServerOnline(serverName)) {
                return true;
            }
        }
        return false;
    }

    public int getMainServerMaxPlayers() {
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (!udpPreferred) {
            ServerStatus status = getMainServerStatus();
            int maxPlayers = status != null ? status.getMaxPlayers() : 0;
            // 当目标服务器没有玩家时，BC/VC 代理端会返回 maxPlayers=0
            // 此时应使用本地配置作为备用值，避免队列逻辑认为没有可用槽位
            if (maxPlayers <= 0) {
                maxPlayers = plugin.getConfigValueInt("queue.max-online", 10);
            }
            return maxPlayers;
        }

        // UDP 优先模式下，返回所有在线 UDP 服务器的总容量
        int totalMax = 0;
        for (ServerStatus status : getOnlineMainServers()) {
            totalMax += status.getMaxPlayers();
        }
        return totalMax > 0 ? totalMax : plugin.getConfigValueInt("queue.max-online", 10);
    }

    /**
     * 获取所有在线的主服务器状态列表
     * 当 UDP 优先时，只返回 UDP 配置的服务器
     */
    public List<ServerStatus> getOnlineMainServers() {
        List<ServerStatus> onlineServers = new ArrayList<>();

        // 判断是否为 UDP 优先模式
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        java.util.List<String> udpServerNames = new java.util.ArrayList<>();
        if (udpPreferred) {
            for (java.util.Map<?, ?> map : plugin.getConfigValueMapList("udp-sync.servers")) {
                Object nameObj = map.get("name");
                if (nameObj != null) {
                    udpServerNames.add(String.valueOf(nameObj));
                }
            }
            // 兼容旧配置（单服务器模式）
            if (udpServerNames.isEmpty() && plugin.getConfigValueString("udp-sync.host", null) != null) {
                udpServerNames.add(plugin.getConfigValueString("queue.main-server", "main"));
            }
        }

        for (Map.Entry<String, ServerStatus> entry : serverStatusCache.entrySet()) {
            // UDP 优先模式下，过滤只包含 UDP 配置的服务器
            if (udpPreferred && !udpServerNames.contains(entry.getKey())) {
                continue;
            }
            if (isServerOnline(entry.getKey())) {
                onlineServers.add(entry.getValue());
            }
        }
        return onlineServers;
    }

    /**
     * 根据负载均衡策略选择最优主服务器
     *
     * @return 最优服务器名，无可用服务器返回 null
     */
    public String selectOptimalServer() {
        List<ServerStatus> onlineServers = getOnlineMainServers();
        if (onlineServers.isEmpty()) {
            return null;
        }

        String strategy = plugin.getConfigValueString("queue.balance-strategy", "LEAST_PLAYERS");
        switch (strategy.toUpperCase()) {
            case "LEAST_LOAD":
                return onlineServers.stream()
                        .min(Comparator.comparingDouble(ServerStatus::getLoadRatio))
                        .map(ServerStatus::getServerName)
                        .orElse(null);
            case "ROUND_ROBIN":
                // 轮询：按索引循环选择
                int idx = roundRobinIndex.getAndIncrement() % onlineServers.size();
                return onlineServers.get(idx).getServerName();
            case "RANDOM":
                return onlineServers.get(new java.util.Random().nextInt(onlineServers.size())).getServerName();
            case "LEAST_PLAYERS":
            default:
                return onlineServers.stream()
                        .min(Comparator.comparingInt(ServerStatus::getOnlinePlayers))
                        .map(ServerStatus::getServerName)
                        .orElse(null);
        }
    }

    /**
     * 将玩家连接到最优主服务器（负载均衡）
     */
    public void connectToOptimalServer(Player player) {
        String optimalServer = selectOptimalServer();
        if (optimalServer != null) {
            connectPlayerToServer(player, optimalServer);
        } else {
            // 无可用服务器，回退到默认主服务器
            connectToMainServer(player);
        }
    }

    /**
     * 实时检测主服务器是否在线
     * 发送 ServerInfo 请求并等待响应，超时则视为离线
     * UDP 优先模式下，检测是否有任何 UDP 配置的服务器在线
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return CompletableFuture，完成后返回主服是否在线
     */
    public CompletableFuture<Boolean> checkMainServerOnlineAsync(int timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Player player = getAnyOnlinePlayer();
        if (player == null) {
            future.complete(false);
            return future;
        }

        final long requestTime = System.currentTimeMillis();

        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        boolean mslpPreferred = udpEnabled && "MSLP".equalsIgnoreCase(udpPriority);
        if (mslpPreferred) {
            // MSLP 优先模式下，异步执行 MSLP 检测
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] MSLP 优先模式，异步检测所有 UDP 配置的服务器");
            }
            for (UDPClient client : udpClients) {
                int clientGamePort = client.getGamePort();
                if (clientGamePort > 0) {
                    // 配置了 game-port，直接执行 MSLP
                    new Thread(() -> {
                        ServerStatus status = client.requestServerInfoViaMSLP(null, clientGamePort);
                        if (status != null) {
                            serverStatusCache.put(status.getServerName(), status);
                            lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                        }
                    }).start();
                } else {
                    // 未配置 game-port，通过 BungeeCord ServerIP 获取端口
                    requestServerIPViaBC(client.getServerName());
                }
            }
        } else if (udpPreferred) {
            // UDP 优先模式下，请求所有 UDP 配置的服务器信息
            for (UDPClient client : udpClients) {
                client.requestServerInfoAsync();
            }
        } else if (enabled) {
            // 开启BC扩展时，使用自定义通道
            requestMainServerInfo();
        } else {
            // 关闭BC扩展时，使用BungeeCord原生ServerIP获取IP和端口，然后Minecraft Server List Ping
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 关闭BC扩展模式，使用BungeeCord原生ServerIP + Minecraft Server List Ping检测主服务器");
            }
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ServerIP");
            out.writeUTF(mainServer);
            try {
                player.sendPluginMessage(CHANNEL_BUNGEE_CORD, out.toByteArray());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Limbo.getInstance().getScheduler().runTaskTimer(plugin, new com.loohp.limbo.scheduler.LimboTask() {
            private int ticks = 0;
            private final int maxTicks = timeoutSeconds * 20;

            @Override
            public void run() {
                if (future.isDone()) {
                    return;
                }

                if (udpPreferred) {
                    // UDP 优先模式下，检查是否有任何 UDP 服务器收到了新响应
                    boolean anyUpdated = false;
                    for (UDPClient client : udpClients) {
                        if (lastServerInfoTimeMap.getOrDefault(client.getServerName(), 0L) >= requestTime) {
                            anyUpdated = true;
                            break;
                        }
                    }
                    if (anyUpdated) {
                        future.complete(isMainServerOnline());
                        return;
                    }
                } else if (enabled) {
                    // 开启BC扩展模式
                    if (lastServerInfoTimeMap.getOrDefault(mainServer, 0L) >= requestTime) {
                        future.complete(isMainServerOnline());
                        return;
                    }
                } else {
                    // 关闭BC扩展模式：检查ServerIP响应是否已处理（通过Minecraft Server List Ping更新缓存）
                    if (lastServerInfoTimeMap.getOrDefault(mainServer, 0L) >= requestTime) {
                        future.complete(isMainServerOnline());
                        return;
                    }
                }

                ticks += 2;
                if (ticks >= maxTicks) {
                    future.complete(false);
                }
            }
        }, 2L, 2L);

        return future;
    }

    /**
     * 通知代理端玩家登录成功
     * 用于控制代理端是否允许玩家使用 /server 命令
     */
    public void notifyLoginSuccess(Player player) {
        if (!enabled) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(player.getName());
        out.writeUTF(player.getUniqueId().toString());
        try {
            player.sendPluginMessage(CHANNEL_LOGIN_SUCCESS, out.toByteArray());
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] 发送登录成功通知到代理端: 玩家=" + player.getName());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getMainServer() {
        return mainServer;
    }

    private Player getAnyOnlinePlayer() {
        if (Limbo.getInstance().getPlayers().isEmpty()) return null;
        return Limbo.getInstance().getPlayers().iterator().next();
    }

    /**
     * 根据服务器名称获取 UDPClient
     */
    private UDPClient getUDPClientByServerName(String serverName) {
        for (UDPClient client : udpClients) {
            if (client.getServerName().equals(serverName)) {
                return client;
            }
        }
        return null;
    }

    /**
     * 安全地将对象解析为整数
     */
    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 判断是否为 BungeeCord/Velocity 原生插件消息通道
     * Velocity 使用 bungeecord:main，BungeeCord 使用 BungeeCord
     */
    private boolean isBungeeCordChannel(String channel) {
        return CHANNEL_BUNGEE_CORD.equals(channel)
                || "bungeecord:main".equals(channel)
                || channel != null && channel.toLowerCase().startsWith("bungeecord");
    }

    public void onPluginMessageReceived(com.loohp.limbo.events.player.PluginMessageEvent event) {
        String channel = event.getChannel();
        byte[] message = event.getData();
        Player player = event.getPlayer();

        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 通道=" + channel + " 数据长度=" + message.length + " 玩家=" + player.getName());
        }

        if (isBungeeCordChannel(channel)) {
            // 处理 BungeeCord/Velocity 原生通道响应
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subchannel;
            try {
                subchannel = in.readUTF();
            } catch (Exception e) {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: BungeeCord通道读取subchannel失败: " + e.getMessage());
                }
                return;
            }

            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: BungeeCord子通道=" + subchannel);
            }

            if ("ServerIP".equals(subchannel)) {
                String server;
                String ip;
                int port;
                try {
                    server = in.readUTF();
                    ip = in.readUTF();
                    port = in.readUnsignedShort();
                } catch (Exception e) {
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 读取ServerIP响应失败: " + e.getMessage());
                    }
                    return;
                }
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] ServerIP: 收到 " + server + " 的地址: " + ip + ":" + port);
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 启动异步ServerListPing，目标=" + server + " " + ip + ":" + port);
                }
                // MSLP 优先模式下，通过 UDPClient 执行 MSLP（获取更精确的状态）
                boolean mslpPreferred = udpEnabled && "MSLP".equalsIgnoreCase(udpPriority);
                if (mslpPreferred) {
                    final String targetServer = server;
                    final String targetIp = ip;
                    final int targetPort = port;
                    new Thread(() -> {
                        if (isDebug()) {
                            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] MSLP线程启动: 目标=" + targetServer + " " + targetIp + ":" + targetPort);
                        }
                        UDPClient client = getUDPClientByServerName(targetServer);
                        if (client != null) {
                            ServerStatus status = client.requestServerInfoViaMSLP(targetIp, targetPort);
                            if (status != null) {
                                serverStatusCache.put(targetServer, status);
                                lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                                if (isDebug()) {
                                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] MSLP完成: " + targetServer + " 在线，已更新缓存");
                                }
                            } else {
                                if (isDebug()) {
                                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] MSLP完成: " + targetServer + " 离线，已更新缓存");
                                }
                            }
                        } else {
                            // 回退到原生 pingMinecraftServer
                            ServerStatus status = pingMinecraftServer(targetServer, targetIp, targetPort);
                            serverStatusCache.put(targetServer, status);
                            if (status.isOnline()) {
                                lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                                if (isDebug()) {
                                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] ServerListPing完成: " + targetServer + " 在线，已更新缓存");
                                }
                            } else {
                                if (isDebug()) {
                                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] ServerListPing完成: " + targetServer + " 离线，已更新缓存");
                                }
                            }
                        }
                    }).start();
                } else {
                    // 非 MSLP 优先模式，使用原有的 pingMinecraftServer
                    final String targetServer = server;
                    final String targetIp = ip;
                    final int targetPort = port;
                    new Thread(() -> {
                        if (isDebug()) {
                            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] ServerListPing线程启动: 目标=" + targetServer + " " + targetIp + ":" + targetPort);
                        }
                        ServerStatus status = pingMinecraftServer(targetServer, targetIp, targetPort);
                        serverStatusCache.put(targetServer, status);
                        if (status.isOnline()) {
                            lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                            if (isDebug()) {
                                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] ServerListPing完成: " + targetServer + " 在线，已更新缓存");
                            }
                        } else {
                            if (isDebug()) {
                                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] ServerListPing完成: " + targetServer + " 离线，已更新缓存");
                            }
                        }
                    }).start();
                }
            }
            return;
        }

        if (!enabled) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: enabled=false，忽略自定义通道消息");
            }
            return;
        }

        if (CHANNEL_SERVER_INFO.equals(channel)) {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String type;
            try {
                type = in.readUTF();
            } catch (Exception e) {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 读取自定义通道消息type失败: " + e.getMessage());
                }
                return;
            }

            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 自定义通道消息类型=" + type);
            }

            if ("REQ".equals(type)) {
                // 收到其他服务器（通过BC代理转发）发来的状态查询请求
                // 返回当前服务器的在线状态
                String server;
                try {
                    server = in.readUTF();
                } catch (Exception e) {
                    if (isDebug()) {
                        Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 读取REQ请求server失败: " + e.getMessage());
                    }
                    return;
                }
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("RESP");
                out.writeUTF(server);
                out.writeInt(Limbo.getInstance().getPlayers().size());
                out.writeInt(Limbo.getInstance().getServerProperties().getMaxPlayers());
                out.writeBoolean(true);
                try {
                    player.sendPluginMessage(CHANNEL_SERVER_INFO, out.toByteArray());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] ServerInfo: 响应状态查询请求，服务器=" + server + " 在线=" + Limbo.getInstance().getPlayers().size() + " 最大=" + Limbo.getInstance().getServerProperties().getMaxPlayers());
                }
                return;
            }

            if (!"RESP".equals(type)) {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 未知消息类型=" + type + "，忽略");
                }
                return;
            }

            String server;
            int online;
            int maxPlayers;
            boolean onlineStatus;
            try {
                server = in.readUTF();
                online = in.readInt();
                maxPlayers = in.readInt();
                onlineStatus = in.readBoolean();
            } catch (Exception e) {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 读取RESP响应失败: " + e.getMessage());
                }
                return;
            }

            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] onPluginMessageReceived: 收到RESP响应，服务器=" + server + " 在线=" + online + " 最大=" + maxPlayers + " 状态=" + onlineStatus);
            }

            // 更新缓存中的状态
            serverStatusCache.put(server, new ServerStatus(server, online, maxPlayers, onlineStatus));

            // 更新最后收到响应的时间戳
            // 注意：即使 maxPlayers = 0，只要 onlineStatus = true，也说明服务器进程存在并可通信
            // 根据在线状态判断标准，这种情况应视为在线（BC/VC 代理端在无玩家时返回 maxPlayers=0 是正常行为）
            lastServerInfoTimeMap.put(server, System.currentTimeMillis());
        }
    }

    /**
     * 使用 Minecraft Server List Ping 协议检测服务器状态
     * 与原版客户端查询服务器列表的行为一致
     *
     * @param serverName 服务器名称
     * @param ip 服务器IP
     * @param port 服务器端口
     * @return 服务器状态
     */
    private ServerStatus pingMinecraftServer(String serverName, String ip, int port) {
        if (isDebug()) {
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 开始检测，目标=" + serverName + " " + ip + ":" + port);
        }
        try (Socket socket = new Socket()) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 正在连接...");
            }
            socket.connect(new InetSocketAddress(ip, port), 5000);
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: TCP连接成功");
            }

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 发送握手包
            // Packet ID (VarInt): 0x00
            // Protocol Version (VarInt): -1 (ping)
            // Server Address (String): ip
            // Server Port (Unsigned Short): port
            // Next State (VarInt): 1 (status)
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream handshakeData = new DataOutputStream(handshake);
            writeVarInt(handshakeData, 0x00); // Packet ID
            writeVarInt(handshakeData, -1); // Protocol version (ping)
            writeString(handshakeData, ip);
            handshakeData.writeShort(port);
            writeVarInt(handshakeData, 1); // Next state: status

            byte[] handshakeBytes = handshake.toByteArray();
            writeVarInt(out, handshakeBytes.length);
            out.write(handshakeBytes);
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 握手包已发送");
            }

            // 发送状态请求包
            // Packet ID (VarInt): 0x00
            // Empty payload
            out.writeByte(0x01); // Length: 1
            out.writeByte(0x00); // Packet ID: 0x00
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 状态请求包已发送");
            }

            // 读取响应长度
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 等待响应...");
            }
            int length = readVarInt(in);
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 响应长度=" + length);
            }
            // 读取包ID
            int packetId = readVarInt(in);
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 包ID=" + packetId);
            }
            if (packetId != 0x00) {
                if (isDebug()) {
                    Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 包ID不是0x00，返回离线");
                }
                return new ServerStatus(serverName, 0, 0, false);
            }
            // 读取JSON字符串长度
            int jsonLength = readVarInt(in);
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: JSON长度=" + jsonLength);
            }
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: JSON响应=" + json.substring(0, Math.min(json.length(), 200)));
            }

            // 解析JSON
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            int online = players != null && players.has("online") ? players.get("online").getAsInt() : 0;
            int maxPlayers = players != null && players.has("max") ? players.get("max").getAsInt() : 0;

            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 解析成功，online=" + online + " maxPlayers=" + maxPlayers);
            }

            return new ServerStatus(serverName, online, maxPlayers, true);
        } catch (Exception e) {
            if (isDebug()) {
                Limbo.getInstance().getConsole().sendMessage("[LoginQueue2Limbo] [DEBUG] pingMinecraftServer: 检测失败: " + e.getClass().getName() + ": " + e.getMessage());
            }
            return new ServerStatus(serverName, 0, 0, false);
        }
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        }
        return value;
    }

    /**
     * 服务器状态信息封装类
     */
    public static class ServerStatus {
        private final String serverName;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final boolean online;

        public ServerStatus(String serverName, int onlinePlayers, int maxPlayers, boolean online) {
            this.serverName = serverName;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.online = online;
        }

        public String getServerName() {
            return serverName;
        }

        public int getOnlinePlayers() {
            return onlinePlayers;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public boolean isOnline() {
            return online;
        }

        /**
         * 计算服务器负载比例（0.0 - 1.0）
         */
        public double getLoadRatio() {
            if (maxPlayers <= 0) return 0.0;
            return (double) onlinePlayers / maxPlayers;
        }

        @Override
        public String toString() {
            return serverName + " [" + onlinePlayers + "/" + maxPlayers + "] " + (online ? "在线" : "离线");
        }
    }
}
