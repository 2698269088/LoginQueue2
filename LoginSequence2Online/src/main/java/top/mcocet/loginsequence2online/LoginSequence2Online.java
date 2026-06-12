package top.mcocet.loginsequence2online;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginsequence2online.listener.ServerInfoListener;

import java.lang.reflect.Method;

public final class LoginSequence2Online extends JavaPlugin {

    public static final String CHANNEL_SERVER_INFO = "loginsequence:serverinfo";

    private ServerInfoListener serverInfoListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_SERVER_INFO,
                serverInfoListener = new ServerInfoListener(this));

        getLogger().info("LoginSequence2Online 已启用。");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_SERVER_INFO);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL_SERVER_INFO, serverInfoListener);

        if (isFolia()) {
            cancelFoliaTasks();
        }

        getLogger().info("LoginSequence2Online 已禁用。");
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
