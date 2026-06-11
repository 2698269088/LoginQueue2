package top.mcocet.loginsequence2bc;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import top.mcocet.loginsequence2bc.listener.PluginMessageListener;

public final class LoginSequence2BC extends Plugin {

    public static final String CHANNEL_CONNECT_OTHER = "LoginSequence:ConnectOther";
    public static final String CHANNEL_CONNECT_REQUEST = "LoginSequence:ConnectRequest";
    public static final String CHANNEL_SERVER_INFO = "LoginSequence:ServerInfo";

    private PluginMessageListener messageListener;

    @Override
    public void onEnable() {
        PluginManager pluginManager = getProxy().getPluginManager();

        pluginManager.registerListener(this, messageListener = new PluginMessageListener(this));

        getLogger().info("LoginSequence2BC 已启用。");
    }

    @Override
    public void onDisable() {
        if (messageListener != null) {
            getProxy().getPluginManager().unregisterListener(messageListener);
        }
        getLogger().info("LoginSequence2BC 已禁用。");
    }
}
