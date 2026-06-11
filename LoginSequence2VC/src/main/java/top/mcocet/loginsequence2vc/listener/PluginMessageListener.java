package top.mcocet.loginsequence2vc.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import top.mcocet.loginsequence2vc.LoginSequence2VC;

import java.util.Optional;

public class PluginMessageListener {

    private final LoginSequence2VC plugin;
    private final ProxyServer server;
    private final Logger logger;

    public PluginMessageListener(LoginSequence2VC plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();

        if (LoginSequence2VC.CHANNEL_CONNECT_OTHER.equals(channel)) {
            handleConnectOther(event);
            return;
        }

        if (LoginSequence2VC.CHANNEL_CONNECT_REQUEST.equals(channel)) {
            handleConnectRequest(event);
            return;
        }

        if (LoginSequence2VC.CHANNEL_SERVER_INFO.equals(channel)) {
            // 判断消息来源：来自子服务器的是响应，来自玩家的是请求
            if (event.getSource() instanceof Player) {
                handleServerInfoRequest(event);
            } else if (event.getSource() instanceof RegisteredServer) {
                handleServerInfoResponse(event);
            }
            return;
        }
    }

    /**
     * 处理 ConnectOther 通道消息
     * 格式: [targetPlayer] [targetServer]
     */
    private void handleConnectOther(PluginMessageEvent event) {
        if (!(event.getSource() instanceof Player)) {
            return;
        }

        Player sender = (Player) event.getSource();
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String targetPlayerName;
        String targetServerName;
        try {
            targetPlayerName = in.readUTF();
            targetServerName = in.readUTF();
        } catch (Exception e) {
            logger.warn("ConnectOther 消息格式错误: {}", e.getMessage());
            return;
        }

        Optional<Player> targetOpt = server.getPlayer(targetPlayerName);
        if (targetOpt.isEmpty()) {
            logger.warn("ConnectOther: 玩家 {} 不在线", targetPlayerName);
            return;
        }

        Optional<RegisteredServer> serverOpt = server.getServer(targetServerName);
        if (serverOpt.isEmpty()) {
            logger.warn("ConnectOther: 目标服务器 {} 不存在", targetServerName);
            return;
        }

        Player target = targetOpt.get();
        RegisteredServer targetSrv = serverOpt.get();

        target.createConnectionRequest(targetSrv).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.warn("ConnectOther: 将玩家 {} 转移到 {} 失败: {}",
                        targetPlayerName, targetServerName, throwable.getMessage());
            } else {
                logger.debug("ConnectOther: 已将玩家 {} 转移到 {}", targetPlayerName, targetServerName);
            }
        });

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 处理玩家主动请求连接到自己当前所在服务器之外的其他子服务器
     */
    private void handleConnectRequest(PluginMessageEvent event) {
        if (!(event.getSource() instanceof Player)) {
            return;
        }

        Player sender = (Player) event.getSource();
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String targetServerName;
        try {
            targetServerName = in.readUTF();
        } catch (Exception e) {
            logger.warn("ConnectRequest 消息格式错误: {}", e.getMessage());
            return;
        }

        Optional<RegisteredServer> serverOpt = server.getServer(targetServerName);
        if (serverOpt.isEmpty()) {
            logger.warn("ConnectRequest: 目标服务器 {} 不存在", targetServerName);
            return;
        }

        sender.createConnectionRequest(serverOpt.get()).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.warn("ConnectRequest: 将玩家 {} 转移到 {} 失败: {}",
                        sender.getUsername(), targetServerName, throwable.getMessage());
            } else {
                logger.debug("ConnectRequest: 已将玩家 {} 转移到 {}", sender.getUsername(), targetServerName);
            }
        });

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 处理来自玩家的 ServerInfo 请求
     * 请求格式: [serverName]
     * 策略：将请求转发给目标子服务器，由子服务器插件返回真实信息。
     */
    private void handleServerInfoRequest(PluginMessageEvent event) {
        Player sender = (Player) event.getSource();
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String serverName;
        try {
            serverName = in.readUTF();
        } catch (Exception e) {
            logger.warn("ServerInfo 请求格式错误: {}", e.getMessage());
            return;
        }

        Optional<RegisteredServer> targetOpt = server.getServer(serverName);
        if (targetOpt.isEmpty()) {
            // 目标服务器不存在，直接返回离线状态
            sendServerInfoResponse(sender, serverName, 0, 0, false);
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }

        RegisteredServer targetServer = targetOpt.get();

        // 如果请求者就在目标服务器上，不需要转发，让子服务器自己处理
        if (sender.getCurrentServer().isPresent()
                && sender.getCurrentServer().get().getServerInfo().getName().equals(serverName)) {
            return;
        }

        // 转发 ServerInfo 请求到请求者当前所在子服务器，由该服务器上的 LS2Online 返回信息
        if (sender.getCurrentServer().isEmpty()) {
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(serverName);

        sender.getCurrentServer().get().sendPluginMessage(
                com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(LoginSequence2VC.CHANNEL_SERVER_INFO),
                out.toByteArray()
        );

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 处理来自子服务器的 ServerInfo 响应
     * 响应格式: [serverName] [online] [maxPlayers] [onlineStatus]
     * 策略：将响应转发给当前在该子服务器上的所有玩家。
     */
    private void handleServerInfoResponse(PluginMessageEvent event) {
        RegisteredServer sourceServer = (RegisteredServer) event.getSource();
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());

        String serverName;
        int online;
        int maxPlayers;
        boolean onlineStatus;
        try {
            serverName = in.readUTF();
            online = in.readInt();
            maxPlayers = in.readInt();
            onlineStatus = in.readBoolean();
        } catch (Exception e) {
            logger.warn("ServerInfo 响应格式错误: {}", e.getMessage());
            return;
        }

        // 将响应转发给该子服务器上的所有在线玩家
        for (Player player : sourceServer.getPlayersConnected()) {
            sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus);
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private void sendServerInfoResponse(Player sender, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(maxPlayers);
        out.writeBoolean(onlineStatus);

        sender.getCurrentServer().ifPresent(conn ->
                conn.sendPluginMessage(
                        com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(LoginSequence2VC.CHANNEL_SERVER_INFO),
                        out.toByteArray()
                )
        );
    }
}
