package top.mcocet.loginsequence2online.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2online.LoginSequence2Online;

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
        String serverName;
        try {
            serverName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ServerInfo 请求格式错误: " + e.getMessage());
            return;
        }

        // 如果请求的不是本服务器，忽略（由代理端转发到正确的子服务器）
        String localServerName = plugin.getConfig().getString("server-name", plugin.getServer().getServerName());
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

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }
                broadcastServerInfo();
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    private void broadcastServerInfo() {
        String serverName = plugin.getConfig().getString("server-name", plugin.getServer().getServerName());
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = plugin.getServer().getMaxPlayers();

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
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
