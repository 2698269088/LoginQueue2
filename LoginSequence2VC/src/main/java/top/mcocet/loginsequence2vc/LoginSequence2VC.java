package top.mcocet.loginsequence2vc;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import top.mcocet.loginsequence2vc.listener.PluginMessageListener;

import java.nio.file.Path;

@Plugin(
        id = "loginsequence2vc",
        name = "LoginSequence2VC",
        version = "1.0",
        description = "LoginSequence2 的 Velocity 代理端配套插件",
        authors = {"MCOCET"}
)
public class LoginSequence2VC {

    public static final String CHANNEL_CONNECT_OTHER = "LoginSequence:ConnectOther";
    public static final String CHANNEL_CONNECT_REQUEST = "LoginSequence:ConnectRequest";
    public static final String CHANNEL_SERVER_INFO = "LoginSequence:ServerInfo";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginMessageListener messageListener;

    @Inject
    public LoginSequence2VC(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ChannelIdentifier connectOther = MinecraftChannelIdentifier.from(CHANNEL_CONNECT_OTHER);
        ChannelIdentifier connectRequest = MinecraftChannelIdentifier.from(CHANNEL_CONNECT_REQUEST);
        ChannelIdentifier serverInfo = MinecraftChannelIdentifier.from(CHANNEL_SERVER_INFO);

        server.getChannelRegistrar().register(connectOther);
        server.getChannelRegistrar().register(connectRequest);
        server.getChannelRegistrar().register(serverInfo);

        this.messageListener = new PluginMessageListener(this, server, logger);
        server.getEventManager().register(this, messageListener);

        logger.info("LoginSequence2VC 已启用。");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (messageListener != null) {
            server.getEventManager().unregisterListener(this, messageListener);
        }
        logger.info("LoginSequence2VC 已禁用。");
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }
}
