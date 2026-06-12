package top.mcocet.loginsequence2.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2.LoginSequence;

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
    // 记录最后一次收到 ServerInfo 响应的时间戳
    private volatile long lastServerInfoTime = 0;
    // 子服务器信息请求超时时间（毫秒）
    private static final long SERVER_INFO_TIMEOUT = 10000L;

    public BungeeMessenger(LoginSequence plugin) {
        this(plugin, true);
    }

    public BungeeMessenger(LoginSequence plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.mainServer = plugin.getConfig().getString("queue.main-server", "main");

        if (!enabled) {
            return;
        }

        // 注册自定义消息通道
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_OTHER);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_CONNECT_REQUEST);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_SERVER_INFO);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_SERVER_INFO, this);
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
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 刷新主服务器及所有已缓存子服务器的信息
     */
    public void refresh() {
        requestServerInfo(mainServer);
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
        return status != null && status.isOnline()
                && (System.currentTimeMillis() - lastServerInfoTime) < SERVER_INFO_TIMEOUT;
    }

    /**
     * 将玩家连接到指定子服务器（通过自定义通道，兼容 BungeeCord 和 Velocity）
     */
    public void connectPlayerToServer(Player player, String server) {
        if (!enabled) {
            plugin.getLogger().warning("BungeeMessenger 已禁用，无法发送连接请求");
            return;
        }

        plugin.getLogger().info("发送连接请求: 玩家=" + player.getName() + " 目标服务器=" + server);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(server);
        player.sendPluginMessage(plugin, CHANNEL_CONNECT_REQUEST, out.toByteArray());
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
        ServerStatus status = getMainServerStatus();
        return status != null ? status.getOnlinePlayers() : -1;
    }

    /**
     * 判断主服务器是否在线（基于缓存数据）
     */
    public boolean isMainServerOnline() {
        return isServerOnline(mainServer);
    }

    public int getMainServerMaxPlayers() {
        ServerStatus status = getMainServerStatus();
        return status != null ? status.getMaxPlayers() : plugin.getConfig().getInt("queue.max-online", 10);
    }

    /**
     * 实时检测主服务器是否在线
     * 发送 ServerInfo 请求并等待响应，超时则视为离线
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
        requestMainServerInfo();

        new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = timeoutSeconds * 20;

            @Override
            public void run() {
                if (future.isDone()) {
                    cancel();
                    return;
                }

                if (lastServerInfoTime >= requestTime) {
                    future.complete(isMainServerOnline());
                    cancel();
                    return;
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
            lastServerInfoTime = System.currentTimeMillis();
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
