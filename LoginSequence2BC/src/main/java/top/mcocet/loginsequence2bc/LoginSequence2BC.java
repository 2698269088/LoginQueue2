package top.mcocet.loginsequence2bc;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import top.mcocet.loginsequence2bc.command.LoginSequenceBCCommand;
import top.mcocet.loginsequence2bc.listener.PluginMessageListener;
import top.mcocet.loginsequence2bc.listener.ServerCommandListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public final class LoginSequence2BC extends Plugin {

    public static final String CHANNEL_CONNECT_OTHER = "loginsequence:connectother";
    public static final String CHANNEL_CONNECT_REQUEST = "loginsequence:connectrequest";
    public static final String CHANNEL_SERVER_INFO = "loginsequence:serverinfo";
    public static final String CHANNEL_LOGIN_SUCCESS = "loginsequence:loginsuccess";

    private Configuration config;
    private boolean debug;
    private PluginMessageListener messageListener;
    private ServerCommandListener serverCommandListener;

    @Override
    public void onEnable() {
        loadConfig();

        // 注册自定义插件消息通道（必须注册才能接收子服务器发来的消息）
        getProxy().registerChannel(CHANNEL_CONNECT_OTHER);
        getProxy().registerChannel(CHANNEL_CONNECT_REQUEST);
        getProxy().registerChannel(CHANNEL_SERVER_INFO);
        getProxy().registerChannel(CHANNEL_LOGIN_SUCCESS);

        PluginManager pluginManager = getProxy().getPluginManager();
        pluginManager.registerListener(this, messageListener = new PluginMessageListener(this));
        pluginManager.registerListener(this, serverCommandListener = new ServerCommandListener(this, messageListener));
        pluginManager.registerCommand(this, new LoginSequenceBCCommand(this));

        getLogger().info("LoginSequence2BC 已启用。");
    }

    @Override
    public void onDisable() {
        if (messageListener != null) {
            getProxy().getPluginManager().unregisterListener(messageListener);
        }
        getProxy().getPluginManager().unregisterCommands(this);

        // 注销自定义插件消息通道
        getProxy().unregisterChannel(CHANNEL_CONNECT_OTHER);
        getProxy().unregisterChannel(CHANNEL_CONNECT_REQUEST);
        getProxy().unregisterChannel(CHANNEL_SERVER_INFO);
        getProxy().unregisterChannel(CHANNEL_LOGIN_SUCCESS);

        getLogger().info("LoginSequence2BC 已禁用。");
    }

    public void loadConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                getLogger().warning("无法保存默认配置文件: " + e.getMessage());
            }
        }
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
        } catch (IOException e) {
            getLogger().warning("无法加载配置文件: " + e.getMessage());
            config = new Configuration();
        }
        debug = config.getBoolean("debug", false);
    }

    public void saveConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        try {
            ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, configFile);
        } catch (IOException e) {
            getLogger().warning("无法保存配置文件: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        loadConfig();
    }

    public Configuration getConfig() {
        return config;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        config.set("debug", debug);
        saveConfig();
    }

    public void debug(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
