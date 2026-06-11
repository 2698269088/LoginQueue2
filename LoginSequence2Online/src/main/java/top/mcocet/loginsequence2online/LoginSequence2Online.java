package top.mcocet.loginsequence2online;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginsequence2online.listener.ServerInfoListener;

public final class LoginSequence2Online extends JavaPlugin {

    public static final String CHANNEL_SERVER_INFO = "LoginSequence:ServerInfo";

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
        getLogger().info("LoginSequence2Online 已禁用。");
    }
}
