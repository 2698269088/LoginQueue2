package top.mcocet.loginsequence2online.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2online.LoginSequence2Online;

import java.lang.reflect.Method;

public class ServerInfoListener implements PluginMessageListener {

    private final LoginSequence2Online plugin;

    public ServerInfoListener(LoginSequence2Online plugin) {
        this.plugin = plugin;
        startRefreshTask();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!LoginSequence2Online.CHANNEL_SERVER_INFO.equals(channel)) {
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

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(max);
        out.writeBoolean(onlineStatus);

        player.sendPluginMessage(plugin, LoginSequence2Online.CHANNEL_SERVER_INFO, out.toByteArray());
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

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(max);
        out.writeBoolean(true);

        byte[] data = out.toByteArray();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendPluginMessage(plugin, LoginSequence2Online.CHANNEL_SERVER_INFO, data);
        }
    }
}
