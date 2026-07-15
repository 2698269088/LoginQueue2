package top.mcocet.loginqueue2online;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2online.command.ConnectCommand;
import top.mcocet.loginqueue2online.listener.ServerInfoListener;
import top.mcocet.loginqueue2online.udp.UDPServer;

import java.lang.reflect.Method;

public final class LoginQueue2Online extends JavaPlugin {

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.4";

    public static final String CHANNEL_SERVER_INFO = "loginqueue2:serverinfo";

    private ServerInfoListener serverInfoListener;
    private UDPServer udpServer;

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
        }

        getLogger().info("LoginQueue2Online 已启用。");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_SERVER_INFO, serverInfoListener);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");

        if (udpServer != null) {
            udpServer.stop();
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
}
