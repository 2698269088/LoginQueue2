package top.mcocet.loginqueue2online;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2online.api.MinigameAPI;
import top.mcocet.loginqueue2online.command.ConnectCommand;
import top.mcocet.loginqueue2online.command.MatchCommand;
import top.mcocet.loginqueue2online.listener.ServerInfoListener;
import top.mcocet.loginqueue2online.match.MatchManager;
import top.mcocet.loginqueue2online.udp.UDPServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginQueue2Online extends JavaPlugin implements Listener {

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.7.0";

    public static final String CHANNEL_SERVER_INFO = "loginqueue2:serverinfo";

    private ServerInfoListener serverInfoListener;
    private UDPServer udpServer;
    private top.mcocet.loginqueue2online.udp.UDPClient mainPluginClient;
    private MatchManager matchManager;

    // 虚拟排队玩家状态：UUID -> 目标服务器与当前队列信息
    private final Map<UUID, VirtualQueueEntry> virtualQueueMap = new ConcurrentHashMap<>();

    // 主插件通过 UDP 同步的服务器列表：内部名 -> 显示名
    private final Map<String, String> remoteServerList = Collections.synchronizedMap(new LinkedHashMap<>());

    // 主插件排队队列缓存（MATCH_QUEUE_INFO 推送，供 MinigameAPI 查询）
    private volatile int queueSizeCache = 0;
    private volatile List<UUID> queuePlayersCache = Collections.emptyList();
    private volatile long queueInfoTimestamp = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_SERVER_INFO,
                serverInfoListener = new ServerInfoListener(this));

        // 注册 BungeeCord 通道（用于 /connect 指令）
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // 注册 /connect 指令
        ConnectCommand connectCommand = new ConnectCommand(this);
        getCommand("connect").setExecutor(connectCommand);
        getCommand("connect").setTabCompleter(connectCommand);

        // 注册 /lq2match 对局管理指令
        MatchCommand matchCommand = new MatchCommand(this);
        getCommand("lq2match").setExecutor(matchCommand);
        getCommand("lq2match").setTabCompleter(matchCommand);

        // 启动 UDP 服务端
        if (getConfig().getBoolean("udp-sync.enabled", false)) {
            int udpPort = getConfig().getInt("udp-sync.port", 25566);
            udpServer = new UDPServer(this, udpPort);
            udpServer.start();

            // 初始化向主插件发送 /connect 请求的 UDP 客户端
            initMainPluginClient();
        }

        // 初始化小游戏对局管理器
        this.matchManager = new MatchManager(this);
        if (matchManager.isEnabled()) {
            startMatchReportTask();
            getLogger().info("小游戏对局功能已启用，上报间隔: " + getConfig().getInt("match.report-interval", 3) + "秒");
        }

        // 注册公开 API（供同服小游戏插件调用）
        MinigameAPI.register(this);

        // 监听玩家下线，取消虚拟排队
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("LoginQueue2Online 已启用。");
    }

    private void initMainPluginClient() {
        boolean enabled = getConfig().getBoolean("udp-sync.main-plugin.enabled", false);
        if (!enabled) {
            return;
        }
        String host = getConfig().getString("udp-sync.main-plugin.host", "127.0.0.1");
        int port = getConfig().getInt("udp-sync.main-plugin.port", 16648);
        String secretKey = getConfig().getString("udp-sync.main-plugin.secret-key", "");
        int timeout = getConfig().getInt("udp-sync.main-plugin.timeout", 3000);
        String serverName = getConfig().getString("server-name", Bukkit.getServer().getName());

        mainPluginClient = new top.mcocet.loginqueue2online.udp.UDPClient(this, serverName, host, port, secretKey, timeout);
        if (mainPluginClient.init()) {
            getLogger().info("UDP 主插件客户端已连接: " + host + ":" + port);
        } else {
            getLogger().warning("UDP 主插件客户端连接失败，/connect 虚拟排队功能不可用。");
            mainPluginClient = null;
        }
    }

    /**
     * 启动对局状态上报任务
     * 定期向主插件（登录服）上报本服对局列表，由主插件据此放行排队玩家
     */
    private void startMatchReportTask() {
        int interval = Math.max(1, getConfig().getInt("match.report-interval", 3));
        long ticks = interval * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (matchManager == null) {
                return;
            }
            matchManager.cleanup();
            if (mainPluginClient == null || !mainPluginClient.isInitialized()) {
                return;
            }
            mainPluginClient.sendMatchReport(matchManager.buildReportPayload());
            // 顺带查询登录服排队队列，结果由 MATCH_QUEUE_INFO 推送回并缓存
            mainPluginClient.sendQueueQuery();
        }, ticks, ticks);
    }

    /**
     * 处理主插件发送的对局放行通知（MATCH_JOIN）
     */
    public void handleMatchJoin(UUID uuid, String matchId) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (matchManager == null) {
                return;
            }
            if (!matchManager.handleMatchJoin(uuid, matchId)) {
                getLogger().warning("收到对局放行通知但对局不存在: " + matchId + "（玩家 " + uuid + "）");
            }
        });
    }

    /**
     * 处理主插件发送的开始游戏通知（MATCH_START）
     *
     * @param reason 开始原因，如 TIMEOUT
     */
    public void handleMatchStart(String matchId, String reason) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (matchManager == null) {
                return;
            }
            matchManager.handleMatchStart(matchId, reason);
        });
    }

    /**
     * 处理主插件发送的结束游戏通知（MATCH_END）
     *
     * @param reason 结束原因，如 MANUAL
     */
    public void handleMatchEnd(String matchId, String reason) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (matchManager == null) {
                return;
            }
            if (!matchManager.endMatch(matchId, reason)) {
                getLogger().warning("收到结束游戏通知但对局不存在或已结束: " + matchId);
            }
        });
    }

    /**
     * 处理主插件推送的排队队列信息（MATCH_QUEUE_INFO）
     * 由 UDP 线程调用，内部调度回主线程更新缓存
     */
    public void handleMatchQueueInfo(int queueSize, List<UUID> players) {
        final int size = Math.max(0, queueSize);
        final List<UUID> snapshot = Collections.unmodifiableList(new ArrayList<>(players));
        Bukkit.getScheduler().runTask(this, () -> {
            queueSizeCache = size;
            queuePlayersCache = snapshot;
            queueInfoTimestamp = System.currentTimeMillis();
        });
    }

    @Override
    public void onDisable() {
        MinigameAPI.unregister();

        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_SERVER_INFO, serverInfoListener);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");

        if (udpServer != null) {
            udpServer.stop();
        }
        if (mainPluginClient != null) {
            mainPluginClient.shutdown();
        }

        if (isFolia()) {
            cancelFoliaTasks();
        }

        getLogger().info("LoginQueue2Online 已禁用。");
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void cancelFoliaTasks() {
        try {
            Method getGlobalRegionScheduler = getServer().getClass().getMethod("getGlobalRegionScheduler");
            Object scheduler = getGlobalRegionScheduler.invoke(getServer());
            Method cancelTasks = scheduler.getClass().getMethod("cancelTasks", JavaPlugin.class);
            cancelTasks.invoke(scheduler, this);
        } catch (Exception e) {
            getLogger().warning("取消 Folia 调度任务失败: " + e.getMessage());
        }
    }

    /**
     * 判断 /connect 是否使用 UDP 虚拟排队模式
     */
    public boolean isVirtualQueueEnabled() {
        return getConfig().getBoolean("udp-sync.enabled", false)
                && getConfig().getBoolean("udp-sync.main-plugin.enabled", false)
                && mainPluginClient != null
                && mainPluginClient.isInitialized();
    }

    /**
     * 向主插件发送 /connect 虚拟排队请求
     */
    public void requestVirtualQueue(Player player, String targetServer) {
        if (mainPluginClient == null) {
            player.sendMessage("§c当前服务器未启用虚拟排队功能。");
            return;
        }
        virtualQueueMap.put(player.getUniqueId(), new VirtualQueueEntry(targetServer));
        mainPluginClient.sendConnectRequest(player.getUniqueId(), player.getName(), targetServer, 0)
                .whenComplete((success, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline()) {
                        virtualQueueMap.remove(player.getUniqueId());
                        return;
                    }
                    if (!success) {
                        virtualQueueMap.remove(player.getUniqueId());
                        player.sendMessage("§c虚拟排队请求发送失败，请稍后重试。");
                    }
                }));
    }

    /**
     * 处理主插件返回的连接请求响应
     */
    public void handleVirtualQueueResponse(UUID uuid, boolean success, int position, int online, int max, String message) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                virtualQueueMap.remove(uuid);
                return;
            }
            VirtualQueueEntry entry = virtualQueueMap.get(uuid);
            if (entry == null) {
                return;
            }
            entry.setPosition(position);
            entry.setOnline(online);
            entry.setMax(max);

            if (!success) {
                virtualQueueMap.remove(uuid);
                player.sendMessage("§c" + (message != null && !message.isEmpty() ? message : "加入队列失败。"));
                return;
            }

            player.sendMessage(getConfig().getString("messages.virtual-queue-status",
                    "&a[队列] &f当前排在第 &e{position} &f位，目标服在线 &e{online}&f/&e{max}&f。")
                    .replace("&", "§")
                    .replace("{position}", String.valueOf(position))
                    .replace("{online}", String.valueOf(online))
                    .replace("{max}", String.valueOf(max)));
        });
    }

    /**
     * 处理主插件广播的队列状态
     */
    public void handleVirtualQueueStatus(UUID uuid, int position, int online, int max) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                virtualQueueMap.remove(uuid);
                return;
            }
            VirtualQueueEntry entry = virtualQueueMap.get(uuid);
            if (entry == null) {
                return;
            }
            entry.setPosition(position);
            entry.setOnline(online);
            entry.setMax(max);

            if (position <= 0) {
                return;
            }

            player.sendMessage(getConfig().getString("messages.virtual-queue-status",
                    "&a[队列] &f当前排在第 &e{position} &f位，目标服在线 &e{online}&f/&e{max}&f。")
                    .replace("&", "§")
                    .replace("{position}", String.valueOf(position))
                    .replace("{online}", String.valueOf(online))
                    .replace("{max}", String.valueOf(max)));
        });
    }

    /**
     * 处理主插件发送的放行通知，执行 BungeeCord 跳转
     */
    public void handleVirtualQueueAllow(UUID uuid, String targetServer) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                virtualQueueMap.remove(uuid);
                return;
            }
            virtualQueueMap.remove(uuid);
            sendPlayerToServer(player, targetServer);
            player.sendMessage("§a队列排到，正在将你转移到服务器: " + targetServer);
        });
    }

    /**
     * 使用 BungeeCord 通道将玩家发送到指定服务器
     */
    public void sendPlayerToServer(Player player, String serverName) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(out);
        try {
            data.writeUTF("Connect");
            data.writeUTF(serverName);
        } catch (java.io.IOException e) {
            getLogger().warning("构建 Connect 数据失败: " + e.getMessage());
        }
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    /**
     * 判断玩家是否正在虚拟排队中
     */
    public boolean isInVirtualQueue(UUID uuid) {
        return virtualQueueMap.containsKey(uuid);
    }

    /**
     * 更新主插件通过 UDP 同步的服务器列表（内部名 -> 显示名）
     */
    public void updateRemoteServerList(Map<String, String> servers) {
        synchronized (remoteServerList) {
            remoteServerList.clear();
            if (servers != null) {
                remoteServerList.putAll(servers);
            }
        }
    }

    /**
     * 获取主插件同步的服务器列表副本（内部名 -> 显示名）
     */
    public Map<String, String> getRemoteServerList() {
        synchronized (remoteServerList) {
            return new LinkedHashMap<>(remoteServerList);
        }
    }

    /**
     * 是否已收到主插件同步的服务器列表
     */
    public boolean hasRemoteServerList() {
        return !remoteServerList.isEmpty();
    }

    /**
     * 将玩家输入解析为内部服务器名
     * 优先匹配内部名，其次匹配显示名（忽略颜色代码，大小写不敏感）
     * 列表未同步时返回输入本身，不存在时返回 null
     */
    public String resolveServerName(String input) {
        Map<String, String> snapshot = getRemoteServerList();
        if (snapshot.isEmpty()) {
            return input;
        }
        // 内部名优先
        for (String name : snapshot.keySet()) {
            if (name.equalsIgnoreCase(input)) {
                return name;
            }
        }
        // 其次匹配显示名
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String displayName = entry.getValue();
            if (displayName == null || displayName.isEmpty()) {
                continue;
            }
            String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', displayName));
            if (plain.equalsIgnoreCase(input)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 移除玩家的虚拟排队状态
     */
    public void removeVirtualQueuePlayer(UUID uuid) {
        virtualQueueMap.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (matchManager != null) {
            matchManager.onPlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (virtualQueueMap.remove(uuid) != null && mainPluginClient != null && mainPluginClient.isInitialized()) {
            mainPluginClient.sendCancelRequest(uuid);
        }
        if (matchManager != null) {
            matchManager.onPlayerQuit(uuid);
        }
    }

    public UDPServer getUdpServer() {
        return udpServer;
    }

    public top.mcocet.loginqueue2online.udp.UDPClient getMainPluginClient() {
        return mainPluginClient;
    }

    public MatchManager getMatchManager() {
        return matchManager;
    }

    /**
     * 登录服排队队列人数缓存（最近一次 MATCH_QUEUE_INFO 同步值）
     */
    public int getQueueSizeCache() {
        return queueSizeCache;
    }

    /**
     * 登录服排队队列玩家列表缓存（不可变副本，最多 100 个）
     */
    public List<UUID> getQueuePlayersCache() {
        return queuePlayersCache;
    }

    /**
     * 队列信息缓存时间戳（毫秒），0 表示从未同步
     */
    public long getQueueInfoTimestamp() {
        return queueInfoTimestamp;
    }

    /**
     * 主动请求主插件刷新排队队列信息（异步，结果更新到缓存）
     */
    public void requestQueueInfoRefresh() {
        if (mainPluginClient != null && mainPluginClient.isInitialized()) {
            mainPluginClient.sendQueueQuery();
        }
    }

    /**
     * 请求主插件将排队中的指定玩家放行进入指定对局（异步）
     *
     * @return 请求是否已发送
     */
    public boolean requestPlayerRelease(UUID playerUuid, String matchId) {
        if (mainPluginClient == null || !mainPluginClient.isInitialized()) {
            return false;
        }
        mainPluginClient.sendMatchReleaseRequest(playerUuid, matchId);
        return true;
    }

    /**
     * 虚拟排队条目
     */
    public static class VirtualQueueEntry {
        private final String targetServer;
        private int position;
        private int online;
        private int max;

        public VirtualQueueEntry(String targetServer) {
            this.targetServer = targetServer;
        }

        public String getTargetServer() {
            return targetServer;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public int getOnline() {
            return online;
        }

        public void setOnline(int online) {
            this.online = online;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }
    }
}
