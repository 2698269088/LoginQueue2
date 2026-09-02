package top.mcocet.loginqueue2vc;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import top.mcocet.loginqueue2vc.command.LoginQueue2VCCommand;
import top.mcocet.loginqueue2vc.database.DatabaseManager;
import top.mcocet.loginqueue2vc.listener.IPLimitListener;
import top.mcocet.loginqueue2vc.listener.PluginMessageListener;
import top.mcocet.loginqueue2vc.listener.ServerCommandListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Plugin(
        id = "loginqueue2vc",
        name = "LoginQueue2VC",
        version = "1.0",
        description = "LoginQueue2 的 Velocity 代理端配套插件",
        authors = {"MCOCET"}
)
public class LoginQueue2VC {

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.5";

    public static final String CHANNEL_CONNECT_OTHER = "loginqueue2:connectother";
    public static final String CHANNEL_CONNECT_REQUEST = "loginqueue2:connectrequest";
    public static final String CHANNEL_SERVER_INFO = "loginqueue2:serverinfo";
    public static final String CHANNEL_LOGIN_SUCCESS = "loginqueue2:loginsuccess";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private boolean debug;
    private PluginMessageListener messageListener;
    private DatabaseManager databaseManager;
    private IPLimitListener ipLimitListener;

    @Inject
    public LoginQueue2VC(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

        // 初始化SQLite数据库
        databaseManager = new DatabaseManager(this);
        try {
            databaseManager.init();
            logger.info("SQLite数据库已初始化。");
        } catch (Exception e) {
            logger.warn("SQLite数据库初始化失败: " + e.getMessage());
        }

        ChannelIdentifier connectOther = MinecraftChannelIdentifier.from(CHANNEL_CONNECT_OTHER);
        ChannelIdentifier connectRequest = MinecraftChannelIdentifier.from(CHANNEL_CONNECT_REQUEST);
        ChannelIdentifier serverInfo = MinecraftChannelIdentifier.from(CHANNEL_SERVER_INFO);
        ChannelIdentifier loginSuccess = MinecraftChannelIdentifier.from(CHANNEL_LOGIN_SUCCESS);

        server.getChannelRegistrar().register(connectOther);
        server.getChannelRegistrar().register(connectRequest);
        server.getChannelRegistrar().register(serverInfo);
        server.getChannelRegistrar().register(loginSuccess);

        this.messageListener = new PluginMessageListener(this, server, logger);
        server.getEventManager().register(this, messageListener);
        server.getEventManager().register(this, new ServerCommandListener(this, messageListener));
        server.getEventManager().register(this, ipLimitListener = new IPLimitListener(this, server, databaseManager));

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("lsvc")
                .aliases("loginqueuevc")
                .plugin(this)
                .build();
        commandManager.register(meta, new LoginQueue2VCCommand(this));

        logger.info("LoginQueue2VC 已启用。");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (messageListener != null) {
            server.getEventManager().unregisterListener(this, messageListener);
        }
        if (ipLimitListener != null) {
            server.getEventManager().unregisterListener(this, ipLimitListener);
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        logger.info("LoginQueue2VC 已禁用。");
    }

    private void loadConfig() {
        Path configFile = dataDirectory.resolve("config.properties");
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    } else {
                        Files.write(configFile, "debug=false".getBytes());
                    }
                }
            } catch (IOException e) {
                logger.warn("无法保存默认配置文件: " + e.getMessage());
            }
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("无法加载配置文件: " + e.getMessage());
        }
        debug = Boolean.parseBoolean(props.getProperty("debug", "false"));
    }

    public void reloadConfig() {
        loadConfig();
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        Path configFile = dataDirectory.resolve("config.properties");
        try {
            Files.write(configFile, ("debug=" + debug).getBytes());
        } catch (IOException e) {
            logger.warn("无法保存配置文件: " + e.getMessage());
        }
    }

    public void debug(String message) {
        if (debug) {
            logger.info("[DEBUG] " + message);
        }
    }

    public ProxyServer getProxyServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * 读取配置项（布尔值）
     */
    public boolean getConfigBoolean(String key, boolean defaultValue) {
        Path configFile = dataDirectory.resolve("config.properties");
        if (!Files.exists(configFile)) {
            return defaultValue;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("无法加载配置文件: " + e.getMessage());
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 读取配置项（字符串）
     */
    public String getConfigString(String key, String defaultValue) {
        Path configFile = dataDirectory.resolve("config.properties");
        if (!Files.exists(configFile)) {
            return defaultValue;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("无法加载配置文件: " + e.getMessage());
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }
}
