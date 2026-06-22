package top.mcocet.loginsequence2vc;

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
import top.mcocet.loginsequence2vc.command.LoginSequenceVCCommand;
import top.mcocet.loginsequence2vc.listener.PluginMessageListener;
import top.mcocet.loginsequence2vc.listener.ServerCommandListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Plugin(
        id = "loginsequence2vc",
        name = "LoginSequence2VC",
        version = "1.0",
        description = "LoginSequence2 的 Velocity 代理端配套插件",
        authors = {"MCOCET"}
)
public class LoginSequence2VC {

    public static final String CHANNEL_CONNECT_OTHER = "loginsequence:connectother";
    public static final String CHANNEL_CONNECT_REQUEST = "loginsequence:connectrequest";
    public static final String CHANNEL_SERVER_INFO = "loginsequence:serverinfo";
    public static final String CHANNEL_LOGIN_SUCCESS = "loginsequence:loginsuccess";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private boolean debug;
    private PluginMessageListener messageListener;

    @Inject
    public LoginSequence2VC(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

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

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("lsvc")
                .aliases("loginsequencevc")
                .plugin(this)
                .build();
        commandManager.register(meta, new LoginSequenceVCCommand(this));

        logger.info("LoginSequence2VC 已启用。");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (messageListener != null) {
            server.getEventManager().unregisterListener(this, messageListener);
        }
        logger.info("LoginSequence2VC 已禁用。");
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
                logger.warn("无法保存默认配置文件: {}", e.getMessage());
            }
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("无法加载配置文件: {}", e.getMessage());
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
            logger.warn("无法保存配置文件: {}", e.getMessage());
        }
    }

    public void debug(String message) {
        if (debug) {
            logger.info("[DEBUG] {}", message);
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
            logger.warn("无法加载配置文件: {}", e.getMessage());
            return defaultValue;
        }
        String value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
