package top.mcocet.loginqueue2online.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginqueue2online.LoginQueue2Online;

import java.lang.reflect.Method;

public class ServerInfoListener implements PluginMessageListener {

    private final LoginQueue2Online plugin;

    public ServerInfoListener(LoginQueue2Online plugin) {
        this.plugin = plugin;
        startRefreshTask();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!LoginQueue2Online.CHANNEL_SERVER_INFO.equals(channel)) {
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String type;
        String serverName;
        try {
            type = in.readUTF();
            if (!"REQ".equals(type)) {
                return;
            }
            serverName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ServerInfo 请求格式错误: " + e.getMessage());
            return;
        }

        // 如果请求的不是本服务器，忽略（由代理端转发到正确的子服务器）
        String localServerName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());
        if (!localServerName.equals(serverName)) {
            return;
        }

        sendServerInfo(player, serverName);
    }

    private void sendServerInfo(Player player, String serverName) {
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = plugin.getServer().getMaxPlayers();
        boolean onlineStatus = true;
        double tps = getTPS();
        long usedMemory = getUsedMemory();
        long maxMemory = getMaxMemory();
        String protocolVersion = LoginQueue2Online.PROTOCOL_VERSION;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(max);
        out.writeBoolean(onlineStatus);
        out.writeDouble(tps);
        out.writeLong(usedMemory);
        out.writeLong(maxMemory);
        out.writeUTF(protocolVersion);

        player.sendPluginMessage(plugin, LoginQueue2Online.CHANNEL_SERVER_INFO, out.toByteArray());
    }

    private double getTPS() {
        try {
            Object minecraftServer = Bukkit.getServer().getClass().getMethod("getHandle").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) minecraftServer.getClass().getField("recentTps").get(minecraftServer);
            return recentTps[0];
        } catch (Exception e) {
            return 20.0;
        }
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
    }

    private long getMaxMemory() {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024;
    }

    private void startRefreshTask() {
        long interval = plugin.getConfig().getLong("refresh-interval", 5) * 20L;
        if (interval <= 0) {
            interval = 100L;
        }

        Runnable task = () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            broadcastServerInfo();
        };

        if (isFolia()) {
            runFoliaTimer(task, interval);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskTimer(plugin, interval, interval);
        }
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void runFoliaTimer(Runnable task, long interval) {
        try {
            Method getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            Object scheduler = getGlobalRegionScheduler.invoke(Bukkit.getServer());
            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            Method runAtFixedRate = scheduler.getClass().getMethod("runAtFixedRate", JavaPlugin.class, long.class, long.class, scheduledTaskClass);
            runAtFixedRate.invoke(scheduler, plugin, 1L, interval, (Object) null);
        } catch (Exception e) {
            plugin.getLogger().warning("启动 Folia 定时任务失败: " + e.getMessage());
        }
    }

    private void broadcastServerInfo() {
        String serverName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = plugin.getServer().getMaxPlayers();
        double tps = getTPS();
        long usedMemory = getUsedMemory();
        long maxMemory = getMaxMemory();
        String protocolVersion = LoginQueue2Online.PROTOCOL_VERSION;

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(max);
        out.writeBoolean(true);
        out.writeDouble(tps);
        out.writeLong(usedMemory);
        out.writeLong(maxMemory);
        out.writeUTF(protocolVersion);

        byte[] data = out.toByteArray();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendPluginMessage(plugin, LoginQueue2Online.CHANNEL_SERVER_INFO, data);
        }
    }
}
