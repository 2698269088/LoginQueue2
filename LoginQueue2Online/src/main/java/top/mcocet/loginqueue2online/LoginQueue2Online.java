package top.mcocet.loginqueue2online;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2online.command.ConnectCommand;
import top.mcocet.loginqueue2online.listener.ServerInfoListener;
import top.mcocet.loginqueue2online.udp.UDPServer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginQueue2Online extends JavaPlugin implements Listener {

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.5";

    public static final String CHANNEL_SERVER_INFO = "loginqueue2:serverinfo";

    private ServerInfoListener serverInfoListener;
    private UDPServer udpServer;
    private top.mcocet.loginqueue2online.udp.UDPClient mainPluginClient;

    // 虚拟排队玩家状态：UUID -> 目标服务器与当前队列信息
    private final Map<UUID, VirtualQueueEntry> virtualQueueMap = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_SERVER_INFO,
                serverInfoListener = new ServerInfoListener(this));

        // 注册 BungeeCord 通道（用于 /connect 指令）
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // 注册 /connect 指令
        getCommand("connect").setExecutor(new ConnectCommand(this));

        // 启动 UDP 服务端
        if (getConfig().getBoolean("udp-sync.enabled", false)) {
            int udpPort = getConfig().getInt("udp-sync.port", 25566);
            udpServer = new UDPServer(this, udpPort);
            udpServer.start();

            // 初始化向主插件发送 /connect 请求的 UDP 客户端
            initMainPluginClient();
        }

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

    @Override
    public void onDisable() {
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
     * 移除玩家的虚拟排队状态
     */
    public void removeVirtualQueuePlayer(UUID uuid) {
        virtualQueueMap.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (virtualQueueMap.remove(uuid) != null && mainPluginClient != null && mainPluginClient.isInitialized()) {
            mainPluginClient.sendCancelRequest(uuid);
        }
    }

    public UDPServer getUdpServer() {
        return udpServer;
    }

    public top.mcocet.loginqueue2online.udp.UDPClient getMainPluginClient() {
        return mainPluginClient;
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
