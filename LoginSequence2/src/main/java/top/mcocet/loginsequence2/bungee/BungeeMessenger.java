package top.mcocet.loginsequence2.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.udp.UDPClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class BungeeMessenger implements PluginMessageListener {

    /** 自定义消息通道：用于通知代理端将指定玩家转移到目标服务器 */
    public static final String CHANNEL_CONNECT_OTHER = "loginsequence:connectother";
    /** 自定义消息通道：用于玩家主动请求连接到目标服务器 */
    public static final String CHANNEL_CONNECT_REQUEST = "loginsequence:connectrequest";
    /** 自定义消息通道：用于获取指定服务器的状态信息 */
    public static final String CHANNEL_SERVER_INFO = "loginsequence:serverinfo";

    private final LoginSequence plugin;
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

    public BungeeMessenger(LoginSequence plugin) {
        this(plugin, true);
    }

    public BungeeMessenger(LoginSequence plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.mainServer = plugin.getConfig().getString("queue.main-server", "main");
        this.udpEnabled = plugin.getConfig().getBoolean("udp-sync.enabled", false);
        this.udpPriority = plugin.getConfig().getString("udp-sync.priority", "BC_CHANNEL");

        if (!enabled) {
            return;
        }

        // 注册自定义消息通道
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_OTHER);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_REQUEST);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_SERVER_INFO);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_SERVER_INFO, this);

        // 注册 BungeeCord 原生通道（用于 UDP 优先模式下直接转移玩家）
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");

        // 初始化 UDP 客户端（支持多主服务器）
        if (udpEnabled) {
            initUDPClients();
        }
    }

    private boolean isDebug() {
        return plugin.isDebug();
    }

    private void initUDPClients() {
        String plannedKey = plugin.getConfig().getString("udp-sync.planned-key", "");
        int udpTimeout = plugin.getConfig().getInt("udp-sync.timeout", 3000);

        if (isDebug()) {
            plugin.getLogger().info("UDP 初始化开始，planned-key: " + (plannedKey != null && !plannedKey.isEmpty() ? "已配置" : "未配置") + "，超时: " + udpTimeout + "ms");
        }

        // 多服务器模式 - 支持 YAML 列表格式
        List<Map<?, ?>> serversList = plugin.getConfig().getMapList("udp-sync.servers");
        if (serversList != null && !serversList.isEmpty()) {
            if (isDebug()) {
                plugin.getLogger().info("UDP 多服务器模式，配置数量: " + serversList.size());
            }
            int index = 0;
            for (Map<?, ?> serverMap : serversList) {
                index++;
                Object nameObj = serverMap.get("name");
                String serverName = nameObj != null ? String.valueOf(nameObj) : "main" + index;
                Object hostObj = serverMap.get("host");
                String host = hostObj != null ? String.valueOf(hostObj) : "127.0.0.1";
                int port = parseInt(serverMap.get("port"), 25566);
                Object keyObj = serverMap.get("secret-key");
                String secretKey = keyObj != null ? String.valueOf(keyObj) : "";

                if (isDebug()) {
                    plugin.getLogger().info("UDP 正在初始化客户端 [" + serverName + "] -> " + host + ":" + port);
                }
                UDPClient client = new UDPClient(plugin, serverName, host, port, udpTimeout, secretKey, plannedKey);
                if (client.init()) {
                    udpClients.add(client);
                    plugin.getLogger().info("UDP 同步已配置 [" + serverName + "]: " + host + ":" + port);
                } else {
                    plugin.getLogger().warning("UDP 客户端 [" + serverName + "] 初始化失败，已跳过。");
                }
            }
        }

        // 兼容旧配置（单服务器模式）
        if (udpClients.isEmpty()) {
            if (isDebug()) {
                plugin.getLogger().info("UDP 单服务器模式（兼容旧配置）");
            }
            String udpHost = plugin.getConfig().getString("udp-sync.host", "127.0.0.1");
            int udpPort = plugin.getConfig().getInt("udp-sync.port", 25566);
            String secretKey = plugin.getConfig().getString("udp-sync.secret-key", "");
            UDPClient client = new UDPClient(plugin, mainServer, udpHost, udpPort, udpTimeout, secretKey, plannedKey);
            if (client.init()) {
                udpClients.add(client);
                plugin.getLogger().info("UDP 同步已配置（单服务器模式）: " + udpHost + ":" + udpPort);
            } else {
                plugin.getLogger().warning("UDP 客户端（单服务器模式）初始化失败。");
            }
        }

        if (isDebug()) {
            plugin.getLogger().info("UDP 初始化完成，成功客户端数: " + udpClients.size());
        }
    }

    public void shutdown() {
        if (!enabled) {
            return;
        }

        // 注销自定义消息通道
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_CONNECT_OTHER);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_CONNECT_REQUEST);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_SERVER_INFO);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_SERVER_INFO, this);

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
            plugin.getLogger().info("开始刷新服务器状态，UDP启用=" + udpEnabled + "，优先级=" + udpPriority);
        }
        if (udpEnabled && "UDP".equalsIgnoreCase(udpPriority)) {
            // 优先使用 UDP
            boolean anySuccess = tryRefreshViaUDP();
            if (isDebug()) {
                plugin.getLogger().info("UDP 刷新结果: " + (anySuccess ? "成功" : "失败"));
            }
            if (!anySuccess) {
                // UDP 失败，回退到 BC 通道
                if (plugin.isDebug()) {
                    plugin.getLogger().info("UDP 获取失败，回退到 BungeeCord 通道");
                }
                tryRefreshViaBC();
            }
        } else {
            // 优先使用 BC 通道
            boolean bcSuccess = tryRefreshViaBC();
            if (isDebug()) {
                plugin.getLogger().info("BC 通道刷新结果: " + (bcSuccess ? "成功" : "失败"));
            }
            if (!bcSuccess && udpEnabled) {
                // BC 通道失败，回退到 UDP
                if (plugin.isDebug()) {
                    plugin.getLogger().info("BungeeCord 通道获取失败，回退到 UDP");
                }
                tryRefreshViaUDP();
            }
        }
        if (isDebug()) {
            plugin.getLogger().info("刷新完成，当前缓存服务器数: " + serverStatusCache.size());
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
            plugin.getLogger().info("通过 UDP 刷新，客户端数量: " + udpClients.size());
        }
        for (UDPClient client : udpClients) {
            if (isDebug()) {
                plugin.getLogger().info("UDP 请求服务器信息: [" + client.getServerName() + "]");
            }
            ServerStatus status = client.requestServerInfo();
            if (status != null) {
                serverStatusCache.put(status.getServerName(), status);
                lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                anySuccess = true;
                if (isDebug()) {
                    plugin.getLogger().info("UDP 获取成功: " + status);
                }
            } else {
                if (isDebug()) {
                    plugin.getLogger().warning("UDP 获取失败: [" + client.getServerName() + "]");
                }
            }
        }
        return anySuccess;
    }

    /**
     * 尝试通过 BungeeCord 通道获取所有已配置服务器的信息
     *
     * @return 是否至少有一个成功（基于是否有缓存更新）
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

        boolean anySuccess = false;
        for (String server : serversToRequest) {
            long beforeTime = lastServerInfoTimeMap.getOrDefault(server, 0L);
            requestServerInfo(server);
            // 给一点时间等待响应（使用异步延迟避免阻塞主线程）
            final String targetServer = server;
            final long before = beforeTime;
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (lastServerInfoTimeMap.getOrDefault(targetServer, 0L) > before) {
                        if (plugin.isDebug()) {
                            plugin.getLogger().info("BungeeCord 通道获取 " + targetServer + " 成功");
                        }
                    }
                }
            }.runTaskLater(plugin, 10L);
            // 简单判断：如果之前没有缓存，至少请求已发出
            if (serverStatusCache.containsKey(server)) {
                anySuccess = true;
            }
        }
        return anySuccess || !serverStatusCache.isEmpty();
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
        player.sendPluginMessage(plugin, CHANNEL_SERVER_INFO, out.toByteArray());
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
        if (!enabled) {
            plugin.getLogger().warning("BungeeMessenger 已禁用，无法发送连接请求");
            return;
        }

        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (udpPreferred) {
            plugin.getLogger().info("发送连接请求(BungeeCord原生): 玩家=" + player.getName() + " 目标服务器=" + server);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } else {
            plugin.getLogger().info("发送连接请求(自定义通道): 玩家=" + player.getName() + " 目标服务器=" + server);
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_CONNECT_REQUEST, out.toByteArray());
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
        player.sendPluginMessage(plugin, CHANNEL_CONNECT_OTHER, out.toByteArray());
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
        for (java.util.Map<?, ?> map : plugin.getConfig().getMapList("udp-sync.servers")) {
            Object nameObj = map.get("name");
            if (nameObj != null) {
                udpServerNames.add(String.valueOf(nameObj));
            }
        }
        // 兼容旧配置（单服务器模式）
        if (udpServerNames.isEmpty() && plugin.getConfig().getString("udp-sync.host") != null) {
            udpServerNames.add(plugin.getConfig().getString("queue.main-server", "main"));
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
            return status != null ? status.getMaxPlayers() : plugin.getConfig().getInt("queue.max-online", 10);
        }

        // UDP 优先模式下，返回所有在线 UDP 服务器的总容量
        int totalMax = 0;
        for (ServerStatus status : getOnlineMainServers()) {
            totalMax += status.getMaxPlayers();
        }
        return totalMax > 0 ? totalMax : plugin.getConfig().getInt("queue.max-online", 10);
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
            for (java.util.Map<?, ?> map : plugin.getConfig().getMapList("udp-sync.servers")) {
                Object nameObj = map.get("name");
                if (nameObj != null) {
                    udpServerNames.add(String.valueOf(nameObj));
                }
            }
            // 兼容旧配置（单服务器模式）
            if (udpServerNames.isEmpty() && plugin.getConfig().getString("udp-sync.host") != null) {
                udpServerNames.add(plugin.getConfig().getString("queue.main-server", "main"));
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

        String strategy = plugin.getConfig().getString("queue.balance-strategy", "LEAST_PLAYERS");
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

        if (!enabled) {
            future.complete(false);
            return future;
        }

        Player player = getAnyOnlinePlayer();
        if (player == null) {
            future.complete(false);
            return future;
        }

        final long requestTime = System.currentTimeMillis();

        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);
        if (udpPreferred) {
            // UDP 优先模式下，请求所有 UDP 配置的服务器信息
            for (UDPClient client : udpClients) {
                client.requestServerInfoAsync();
            }
        } else {
            requestMainServerInfo();
        }

        new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = timeoutSeconds * 20;

            @Override
            public void run() {
                if (future.isDone()) {
                    cancel();
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
                        cancel();
                        return;
                    }
                } else {
                    if (lastServerInfoTimeMap.getOrDefault(mainServer, 0L) >= requestTime) {
                        future.complete(isMainServerOnline());
                        cancel();
                        return;
                    }
                }

                ticks += 2;
                if (ticks >= maxTicks) {
                    future.complete(false);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);

        return future;
    }

    public String getMainServer() {
        return mainServer;
    }

    private Player getAnyOnlinePlayer() {
        if (plugin.getServer().getOnlinePlayers().isEmpty()) return null;
        return plugin.getServer().getOnlinePlayers().iterator().next();
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

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled) {
            return;
        }

        if (CHANNEL_SERVER_INFO.equals(channel)) {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String type = in.readUTF();
            if (!"RESP".equals(type)) {
                return;
            }
            String server = in.readUTF();
            int online = in.readInt();
            int maxPlayers = in.readInt();
            boolean onlineStatus = in.readBoolean();
            serverStatusCache.put(server, new ServerStatus(server, online, maxPlayers, onlineStatus));
            lastServerInfoTimeMap.put(server, System.currentTimeMillis());
        }
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