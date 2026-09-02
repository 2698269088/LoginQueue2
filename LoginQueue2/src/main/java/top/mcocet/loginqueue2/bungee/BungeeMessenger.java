package top.mcocet.loginqueue2.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.udp.UDPClient;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class BungeeMessenger implements PluginMessageListener {

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.5";

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

    private final LoginQueue2 plugin;
    private final LanguageManager languageManager;
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

    // 记录已报告的版本不匹配信息（避免重复日志）
    private final Set<String> reportedVersionMismatches = ConcurrentHashMap.newKeySet();

    public BungeeMessenger(LoginQueue2 plugin) {
        this(plugin, true);
    }

    public BungeeMessenger(LoginQueue2 plugin, boolean enabled) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.enabled = enabled;
        this.mainServer = plugin.getConfig().getString("queue.main-server", "main");
        this.udpEnabled = plugin.getConfig().getBoolean("udp-sync.enabled", false);
        this.udpPriority = plugin.getConfig().getString("udp-sync.priority", "BC_CHANNEL");

        // 总是注册 BungeeCord 原生通道（关闭BC扩展时用于原生检测）
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_BUNGEE_CORD);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_BUNGEE_CORD, this);

        if (enabled) {
            // 注册自定义消息通道
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_OTHER);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_REQUEST);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_SERVER_INFO);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LOGIN_SUCCESS);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_SERVER_INFO, this);
        }

        // 初始化 UDP 客户端（独立于 BC 扩展，支持多主服务器）
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
            plugin.getLogger().info(languageManager.getLogMessage("udp-init-start", "plannedKey", plannedKey != null && !plannedKey.isEmpty() ? languageManager.getLogMessage("configured") : languageManager.getLogMessage("not-configured"), "timeout", String.valueOf(udpTimeout)));
        }

        // 多服务器模式 - 支持 YAML 列表格式
        List<Map<?, ?>> serversList = plugin.getConfig().getMapList("udp-sync.servers");
        if (serversList != null && !serversList.isEmpty()) {
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("udp-multi-server-mode", "count", String.valueOf(serversList.size())));
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
                    plugin.getLogger().info(languageManager.getLogMessage("udp-init-client", "server", serverName, "host", host, "port", String.valueOf(port), "gamePort", gamePort > 0 ? String.valueOf(gamePort) : ""));
                }
                UDPClient client = new UDPClient(plugin, serverName, host, port, gamePort, udpTimeout, secretKey, plannedKey);
                if (client.init()) {
                    udpClients.add(client);
                    plugin.getLogger().info(languageManager.getLogMessage("udp-sync-configured", "server", serverName, "host", host, "port", String.valueOf(port)));
                } else {
                    plugin.getLogger().warning(languageManager.getLogMessage("udp-client-init-failed", "server", serverName));
                }
            }
        }

        // 兼容旧配置（单服务器模式）
        if (udpClients.isEmpty()) {
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("udp-single-server-mode"));
            }
            String udpHost = plugin.getConfig().getString("udp-sync.host", "127.0.0.1");
            int udpPort = plugin.getConfig().getInt("udp-sync.port", 25566);
            String secretKey = plugin.getConfig().getString("udp-sync.secret-key", "");
            UDPClient client = new UDPClient(plugin, mainServer, udpHost, udpPort, udpTimeout, secretKey, plannedKey);
            if (client.init()) {
                udpClients.add(client);
                plugin.getLogger().info(languageManager.getLogMessage("udp-sync-configured-single", "host", udpHost, "port", String.valueOf(udpPort)));
            } else {
                plugin.getLogger().warning(languageManager.getLogMessage("udp-client-init-failed-single"));
            }
        }

        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-init-complete", "count", String.valueOf(udpClients.size())));
        }
    }

    public void shutdown() {
        // 注销 BungeeCord 原生通道（总是注销）
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_BUNGEE_CORD);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_BUNGEE_CORD, this);

        if (enabled) {
            // 注销自定义消息通道
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_CONNECT_OTHER);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_CONNECT_REQUEST);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_SERVER_INFO);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL_LOGIN_SUCCESS);
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL_SERVER_INFO, this);
        }

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
            plugin.getLogger().info(languageManager.getLogMessage("refresh-start", "udpEnabled", String.valueOf(udpEnabled), "udpPriority", udpPriority));
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
                plugin.getLogger().info(languageManager.getLogMessage("mslp-refresh-result", "success", anySuccess ? languageManager.getLogMessage("success") : languageManager.getLogMessage("failed")));
            }
            if (!anySuccess) {
                if (plugin.isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("mslp-fallback-udp"));
                }
                anySuccess = tryRefreshViaUDP();
                if (!anySuccess && enabled) {
                    if (plugin.isDebug()) {
                        plugin.getLogger().info(languageManager.getLogMessage("udp-fallback-bc"));
                    }
                    tryRefreshViaBC();
                }
            }
        } else if (udpEnabled && "UDP".equalsIgnoreCase(udpPriority)) {
            // 优先使用 UDP
            boolean anySuccess = tryRefreshViaUDP();
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("udp-refresh-result", "success", anySuccess ? languageManager.getLogMessage("success") : languageManager.getLogMessage("failed")));
            }
            if (!anySuccess) {
                // UDP 失败，回退到 BC 通道
                if (plugin.isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("udp-fallback-bc"));
                }
                tryRefreshViaBC();
            }
        } else {
            // 优先使用 BC 通道
            boolean bcSuccess = tryRefreshViaBC();
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("bc-refresh-result", "success", bcSuccess ? languageManager.getLogMessage("success") : languageManager.getLogMessage("failed")));
            }
            if (!bcSuccess && udpEnabled) {
                // BC 通道失败，回退到 UDP
                if (plugin.isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("bc-fallback-udp"));
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
                        plugin.getLogger().info(languageManager.getLogMessage("cache-expired", "server", server, "seconds", String.valueOf((now - lastTime) / 1000)));
                    }
                    serverStatusCache.put(server, new ServerStatus(server, oldStatus.getOnlinePlayers(), oldStatus.getMaxPlayers(), false));
                }
            }
        }

        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("refresh-complete", "count", String.valueOf(serverStatusCache.size())));
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
            plugin.getLogger().info(languageManager.getLogMessage("mslp-refresh-start", "count", String.valueOf(udpClients.size())));
        }
        for (UDPClient client : udpClients) {
            int gamePort = client.getGamePort();
            if (gamePort > 0) {
                // 配置了 game-port，直接执行 MSLP
                if (isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("mslp-direct-request", "server", client.getServerName(), "port", String.valueOf(gamePort)));
                }
                ServerStatus status = client.requestServerInfoViaMSLP(null, gamePort);
                if (status != null) {
                    serverStatusCache.put(status.getServerName(), status);
                    lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                    anyDirectSuccess = true;
                    if (isDebug()) {
                        plugin.getLogger().info(languageManager.getLogMessage("mslp-direct-success", "status", status.toString()));
                    }
                } else {
                    if (isDebug()) {
                        plugin.getLogger().warning(languageManager.getLogMessage("mslp-direct-failed", "server", client.getServerName()));
                    }
                }
            } else {
                // 未配置 game-port，通过 BungeeCord ServerIP 获取端口
                if (isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("mslp-no-game-port", "server", client.getServerName()));
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
        player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, out.toByteArray());
    }

    /**
     * 尝试通过 UDP 获取所有服务器信息
     *
     * @return 是否至少有一个成功
     */
    private boolean tryRefreshViaUDP() {
        boolean anySuccess = false;
        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-refresh-start", "count", String.valueOf(udpClients.size())));
        }
        for (UDPClient client : udpClients) {
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("udp-request-server-info", "server", client.getServerName()));
            }
            ServerStatus status = client.requestServerInfo();
            if (status != null) {
                serverStatusCache.put(status.getServerName(), status);
                lastServerInfoTimeMap.put(status.getServerName(), System.currentTimeMillis());
                anySuccess = true;
                if (isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("udp-get-success", "status", status.toString()));
                }
            } else {
                if (isDebug()) {
                    plugin.getLogger().warning(languageManager.getLogMessage("udp-get-failed", "server", client.getServerName()));
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

        Player player = getAnyOnlinePlayer();
        if (player == null) {
            return !serverStatusCache.isEmpty();
        }

        if (enabled) {
            // 开启BC扩展时，使用自定义通道
            for (String server : serversToRequest) {
                requestServerInfo(server);
            }
        } else {
            // 关闭BC扩展时，使用BungeeCord原生ServerIP + Minecraft Server List Ping
        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("bc-extension-disabled-mode"));
        }
            for (String server : serversToRequest) {
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("ServerIP");
                out.writeUTF(server);
                player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, out.toByteArray());
            }
        }

        // 判断依据：是否有在线玩家可以发送请求，且缓存中已有数据
        // 注意：这不代表本次请求一定成功，只是表示通道可能可用
        return !serverStatusCache.isEmpty();
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

    public List<UDPClient> getUdpClients() {
        return new ArrayList<>(udpClients);
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
            plugin.getLogger().info(languageManager.getLogMessage("connect-bungee-native", "player", player.getName(), "server", server));
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, out.toByteArray());
        } else if (enabled) {
            plugin.getLogger().info(languageManager.getLogMessage("connect-custom-channel", "player", player.getName(), "server", server));
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_CONNECT_REQUEST, out.toByteArray());
        } else {
            // 关闭BC扩展时，使用BungeeCord原生Connect通道
            plugin.getLogger().info(languageManager.getLogMessage("connect-bungee-native", "player", player.getName(), "server", server));
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, out.toByteArray());
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
            int maxPlayers = status != null ? status.getMaxPlayers() : 0;
            // 当目标服务器没有玩家时，BC/VC 代理端会返回 maxPlayers=0
            // 此时应使用本地配置作为备用值，避免队列逻辑认为没有可用槽位
            if (maxPlayers <= 0) {
                maxPlayers = plugin.getConfig().getInt("queue.max-online", 10);
            }
            return maxPlayers;
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
                plugin.getLogger().info(languageManager.getLogMessage("mslp-priority-async-check"));
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
                plugin.getLogger().info(languageManager.getLogMessage("bc-extension-disabled-mode"));
            }
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("ServerIP");
            out.writeUTF(mainServer);
            player.sendPluginMessage(plugin, CHANNEL_BUNGEE_CORD, out.toByteArray());
        }

        SchedulerUtil.runTaskTimer(plugin, new Runnable() {
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
        player.sendPluginMessage(plugin, CHANNEL_LOGIN_SUCCESS, out.toByteArray());
        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("notify-login-success", "player", player.getName()));
        }
    }

    public String getMainServer() {
        return mainServer;
    }

    private Player getAnyOnlinePlayer() {
        if (plugin.getServer().getOnlinePlayers().isEmpty()) return null;
        return plugin.getServer().getOnlinePlayers().iterator().next();
    }

    private double getLocalTPS() {
        try {
            Object minecraftServer = plugin.getServer().getClass().getMethod("getHandle").invoke(plugin.getServer());
            double[] recentTps = (double[]) minecraftServer.getClass().getField("recentTps").get(minecraftServer);
            return recentTps[0];
        } catch (Exception e) {
            return 20.0;
        }
    }

    private long getLocalUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
    }

    private long getLocalMaxMemory() {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024;
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

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (CHANNEL_BUNGEE_CORD.equals(channel)) {
            // 处理 BungeeCord 原生通道响应
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String subchannel;
            try {
                subchannel = in.readUTF();
            } catch (Exception e) {
                return;
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
                    return;
                }
                if (isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("serverip-received", "server", server, "ip", ip, "port", String.valueOf(port)));
                }
                // MSLP 优先模式下，通过 UDPClient 执行 MSLP（获取更精确的状态）
                boolean mslpPreferred = udpEnabled && "MSLP".equalsIgnoreCase(udpPriority);
                if (mslpPreferred) {
                    final String targetServer = server;
                    final String targetIp = ip;
                    final int targetPort = port;
                    new Thread(() -> {
                        UDPClient client = getUDPClientByServerName(targetServer);
                        if (client != null) {
                            ServerStatus status = client.requestServerInfoViaMSLP(targetIp, targetPort);
                            if (status != null) {
                                serverStatusCache.put(targetServer, status);
                                lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                            }
                        } else {
                            // 回退到原生 pingMinecraftServer
                            ServerStatus status = pingMinecraftServer(targetServer, targetIp, targetPort);
                            serverStatusCache.put(targetServer, status);
                            if (status.isOnline()) {
                                lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                            }
                        }
                    }).start();
                } else {
                    // 非 MSLP 优先模式，使用原有的 pingMinecraftServer
                    final String targetServer = server;
                    final String targetIp = ip;
                    final int targetPort = port;
                    new Thread(() -> {
                        ServerStatus status = pingMinecraftServer(targetServer, targetIp, targetPort);
                        serverStatusCache.put(targetServer, status);
                        if (status.isOnline()) {
                            lastServerInfoTimeMap.put(targetServer, System.currentTimeMillis());
                        }
                    }).start();
                }
            }
            return;
        }

        if (!enabled) {
            return;
        }

        if (CHANNEL_SERVER_INFO.equals(channel)) {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String type = in.readUTF();

            if ("REQ".equals(type)) {
                // 收到其他服务器（通过BC代理转发）发来的状态查询请求
                // 返回当前服务器的在线状态
                String server;
                try {
                    server = in.readUTF();
                } catch (Exception e) {
                    return;
                }
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("RESP");
                out.writeUTF(server);
                out.writeInt(plugin.getServer().getOnlinePlayers().size());
                out.writeInt(plugin.getServer().getMaxPlayers());
                out.writeBoolean(true);
                // 添加 TPS 和内存信息
                out.writeDouble(getLocalTPS());
                out.writeLong(getLocalUsedMemory());
                out.writeLong(getLocalMaxMemory());
                player.sendPluginMessage(plugin, CHANNEL_SERVER_INFO, out.toByteArray());
                if (isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("serverinfo-respond", "server", server, "online", String.valueOf(plugin.getServer().getOnlinePlayers().size()), "max", String.valueOf(plugin.getServer().getMaxPlayers())));
                }
                return;
            }

            if (!"RESP".equals(type)) {
                return;
            }
            String server = in.readUTF();
            int online = in.readInt();
            int maxPlayers = in.readInt();
            boolean onlineStatus = in.readBoolean();

            // 尝试读取扩展字段（TPS、内存）
            double tps = 20.0;
            long usedMemory = 0;
            long maxMemory = 0;
            String remoteVersion = null;
            try {
                tps = in.readDouble();
                usedMemory = in.readLong();
                maxMemory = in.readLong();
                // 尝试读取版本字段（新版本Online/BC/VC插件会发送）
                remoteVersion = in.readUTF();
            } catch (Exception e) {
                // 旧版本插件没有这些字段，使用默认值
            }

            // 版本兼容性检查（仅针对 VERSION_CHECK 响应或带有版本号的常规响应）
            if (remoteVersion != null && !remoteVersion.isEmpty()) {
                checkVersionCompatibility(server, remoteVersion);
            }

            // 更新缓存中的状态
            serverStatusCache.put(server, new ServerStatus(server, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory));

            // 更新最后收到响应的时间戳
            // 注意：即使 maxPlayers = 0，只要 onlineStatus = true，也说明服务器进程存在并可通信
            // 根据在线状态判断标准，这种情况应视为在线（BC/VC 代理端在无玩家时返回 maxPlayers=0 是正常行为）
            lastServerInfoTimeMap.put(server, System.currentTimeMillis());
        }
    }

    /**
     * 检查配套插件协议版本是否与主插件兼容
     * @param serverName 服务器名称（或 VERSION_CHECK）
     * @param remoteProtocolVersion 远程插件协议版本
     */
    private void checkVersionCompatibility(String serverName, String remoteProtocolVersion) {
        if (remoteProtocolVersion == null || remoteProtocolVersion.isEmpty()) {
            return;
        }
        if (PROTOCOL_VERSION.equals(remoteProtocolVersion)) {
            return;
        }
        String key = serverName + "|" + remoteProtocolVersion;
        if (!reportedVersionMismatches.contains(key)) {
            reportedVersionMismatches.add(key);
            plugin.getLogger().warning(languageManager.getLogMessage("protocol-version-mismatch-header"));
            plugin.getLogger().warning(languageManager.getLogMessage("version-mismatch-warning", "localVersion", PROTOCOL_VERSION, "server", serverName, "remoteVersion", remoteProtocolVersion));
            plugin.getLogger().warning(languageManager.getLogMessage("version-mismatch-suggestion"));
            plugin.getLogger().warning(languageManager.getLogMessage("version-mismatch-note"));
            plugin.getLogger().warning(languageManager.getLogMessage("protocol-version-mismatch-header"));
        }
    }

    /**
     * 请求代理端（BC/VC）插件的协议版本信息
     */
    public void requestProxyVersion() {
        if (!enabled) return;
        Player player = getAnyOnlinePlayer();
        if (player == null) return;
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("REQ");
        out.writeUTF("VERSION_CHECK");
        player.sendPluginMessage(plugin, CHANNEL_SERVER_INFO, out.toByteArray());
        if (isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("version-check-request-sent"));
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 5000);

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

            // 发送状态请求包
            // Packet ID (VarInt): 0x00
            // Empty payload
            out.writeByte(0x01); // Length: 1
            out.writeByte(0x00); // Packet ID: 0x00

            // 读取响应长度
            int length = readVarInt(in);
            // 读取包ID
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return new ServerStatus(serverName, 0, 0, false);
            }
            // 读取JSON字符串长度
            int jsonLength = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("serverlistping-response", "server", serverName, "json", json.substring(0, Math.min(json.length(), 200))));
            }

            // 解析JSON
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            int online = players != null && players.has("online") ? players.get("online").getAsInt() : 0;
            int maxPlayers = players != null && players.has("max") ? players.get("max").getAsInt() : 0;

            return new ServerStatus(serverName, online, maxPlayers, true);
        } catch (Exception e) {
            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("serverlistping-failed", "server", serverName, "error", e.getMessage()));
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
     * 通过 MSLP 获取指定服务器的在线玩家列表
     * 使用 Minecraft Server List Ping 协议查询服务器状态并解析玩家列表
     *
     * @param serverName 服务器名称
     * @param ip 服务器IP
     * @param port 服务器端口
     * @return 在线玩家列表，失败返回空列表
     */
    public List<String> getServerPlayerListViaMSLP(String serverName, String ip, int port) {
        List<String> playerList = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 5000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 发送握手包
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream handshakeData = new DataOutputStream(handshake);
            writeVarInt(handshakeData, 0x00);
            writeVarInt(handshakeData, -1);
            writeString(handshakeData, ip);
            handshakeData.writeShort(port);
            writeVarInt(handshakeData, 1);

            byte[] handshakeBytes = handshake.toByteArray();
            writeVarInt(out, handshakeBytes.length);
            out.write(handshakeBytes);

            // 发送状态请求包
            out.writeByte(0x01);
            out.writeByte(0x00);

            // 读取响应
            int length = readVarInt(in);
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return playerList;
            }
            int jsonLength = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            // 解析JSON获取玩家列表
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players != null && players.has("sample")) {
                com.google.gson.JsonArray sample = players.getAsJsonArray("sample");
                if (sample != null) {
                    for (int i = 0; i < sample.size(); i++) {
                        JsonObject playerObj = sample.get(i).getAsJsonObject();
                        if (playerObj.has("name")) {
                            playerList.add(playerObj.get("name").getAsString());
                        }
                    }
                }
            }

            if (isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("mslp-playerlist-success", "server", serverName, "count", String.valueOf(playerList.size())));
            }
        } catch (Exception e) {
            if (isDebug()) {
                plugin.getLogger().warning(languageManager.getLogMessage("mslp-playerlist-failed", "server", serverName, "error", e.getMessage()));
            }
        }
        return playerList;
    }

    /**
     * 获取所有子服务器的在线玩家数据（通过 MSLP）
     * 返回每个服务器的玩家列表映射
     */
    public Map<String, List<String>> getAllServerPlayerListsViaMSLP() {
        Map<String, List<String>> result = new HashMap<>();
        if (!udpEnabled) {
            return result;
        }
        for (UDPClient client : udpClients) {
            int gamePort = client.getGamePort();
            if (gamePort > 0) {
                List<String> players = getServerPlayerListViaMSLP(client.getServerName(), client.getHost(), gamePort);
                result.put(client.getServerName(), players);
            } else {
                // 尝试使用 host 和默认游戏端口
                List<String> players = getServerPlayerListViaMSLP(client.getServerName(), client.getHost(), 25565);
                if (!players.isEmpty()) {
                    result.put(client.getServerName(), players);
                }
            }
        }
        return result;
    }

    /**
     * 服务器状态信息封装类
     */
    public static class ServerStatus {
        private final String serverName;
        private final int onlinePlayers;
        private final int maxPlayers;
        private final boolean online;
        private final List<String> playerList;
        private final double tps;
        private final long usedMemory;
        private final long maxMemory;

        public ServerStatus(String serverName, int onlinePlayers, int maxPlayers, boolean online) {
            this(serverName, onlinePlayers, maxPlayers, online, new ArrayList<>(), 20.0, 0, 0);
        }

        public ServerStatus(String serverName, int onlinePlayers, int maxPlayers, boolean online, List<String> playerList) {
            this(serverName, onlinePlayers, maxPlayers, online, playerList, 20.0, 0, 0);
        }

        public ServerStatus(String serverName, int onlinePlayers, int maxPlayers, boolean online, double tps, long usedMemory, long maxMemory) {
            this(serverName, onlinePlayers, maxPlayers, online, new ArrayList<>(), tps, usedMemory, maxMemory);
        }

        public ServerStatus(String serverName, int onlinePlayers, int maxPlayers, boolean online, List<String> playerList, double tps, long usedMemory, long maxMemory) {
            this.serverName = serverName;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.online = online;
            this.playerList = playerList != null ? playerList : new ArrayList<>();
            this.tps = tps;
            this.usedMemory = usedMemory;
            this.maxMemory = maxMemory;
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

        public List<String> getPlayerList() {
            return new ArrayList<>(playerList);
        }

        public double getTps() {
            return tps;
        }

        public long getUsedMemory() {
            return usedMemory;
        }

        public long getMaxMemory() {
            return maxMemory;
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
            return serverName + " [" + onlinePlayers + "/" + maxPlayers + "] TPS=" + String.format("%.1f", tps) + " MEM=" + usedMemory + "MB/" + maxMemory + "MB " + (online ? "ONLINE" : "OFFLINE");
        }
    }
}