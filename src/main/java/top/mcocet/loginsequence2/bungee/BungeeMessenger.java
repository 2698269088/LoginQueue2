package top.mcocet.loginsequence2.bungee;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2.LoginSequence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BungeeMessenger implements PluginMessageListener {

    public static final String BUNGEE_CORD = "BungeeCord";

    private final LoginSequence plugin;
    private final String mainServer;

    // 缓存主服在线人数
    private final ConcurrentHashMap<String, Integer> serverPlayerCount = new ConcurrentHashMap<>();
    // 记录最后一次收到 BungeeCord 响应的时间戳
    private volatile long lastResponseTime = 0;

    public BungeeMessenger(LoginSequence plugin) {
        this.plugin = plugin;
        this.mainServer = plugin.getConfig().getString("queue.main-server", "main");

        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CORD);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE_CORD, this);
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CORD);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BUNGEE_CORD, this);
    }

    /**
     * 请求主服当前在线人数
     */
    public void requestPlayerCount() {
        Player player = getAnyOnlinePlayer();
        if (player == null) return;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(mainServer);
        player.sendPluginMessage(plugin, BUNGEE_CORD, out.toByteArray());
    }

    /**
     * 刷新主服在线人数
     */
    public void refresh() {
        requestPlayerCount();
    }

    /**
     * 将玩家连接到指定服务器
     */
    public void connectPlayer(Player player, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(plugin, BUNGEE_CORD, out.toByteArray());
    }

    /**
     * 将玩家连接到主服务器
     */
    public void connectToMainServer(Player player) {
        connectPlayer(player, mainServer);
    }

    public int getMainServerPlayerCount() {
        return serverPlayerCount.getOrDefault(mainServer, -1);
    }

    /**
     * 判断主服务器是否在线（基于缓存数据）
     * 当 BungeeCord 返回 -1 时，表示目标服务器不在线或无法连接
     */
    public boolean isMainServerOnline() {
        return getMainServerPlayerCount() >= 0;
    }

    /**
     * 实时检测主服务器是否在线
     * 发送 PlayerCount 请求并等待响应，超时则视为离线
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

        // 记录发送请求前的时间戳
        final long requestTime = System.currentTimeMillis();

        // 发送检测请求
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(mainServer);
        player.sendPluginMessage(plugin, BUNGEE_CORD, out.toByteArray());

        // 轮询等待响应或超时
        new BukkitRunnable() {
            private int ticks = 0;
            private final int maxTicks = timeoutSeconds * 20;

            @Override
            public void run() {
                if (future.isDone()) {
                    cancel();
                    return;
                }

                // 如果收到响应的时间在请求发送之后，说明收到了本次请求的响应
                if (lastResponseTime >= requestTime) {
                    int currentCount = serverPlayerCount.getOrDefault(mainServer, -1);
                    future.complete(currentCount >= 0);
                    cancel();
                    return;
                }

                ticks += 2;
                if (ticks >= maxTicks) {
                    future.completeExceptionally(new TimeoutException("主服务器状态检测超时"));
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);

        return future;
    }

    public int getMainServerMaxPlayers() {
        return plugin.getConfig().getInt("queue.max-online", 10);
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
        if (!BUNGEE_CORD.equals(channel)) return;

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();

        if ("PlayerCount".equals(subChannel)) {
            String server = in.readUTF();
            int count = in.readInt();
            serverPlayerCount.put(server, count);
            lastResponseTime = System.currentTimeMillis();
        }
    }
}
