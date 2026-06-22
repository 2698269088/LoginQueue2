package top.mcocet.loginsequence2vc.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import top.mcocet.loginsequence2vc.LoginSequence2VC;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PluginMessageListener {

    private final LoginSequence2VC plugin;
    private final ProxyServer server;
    private final Logger logger;

    // 记录已登录成功的玩家（用于限制 /server 命令）
    private final Set<UUID> loggedInPlayers = new HashSet<>();

    public PluginMessageListener(LoginSequence2VC plugin, ProxyServer server, Logger logger) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();
        String sourceType = event.getSource().getClass().getSimpleName();
        int dataLen = event.getData().length;
        plugin.debug("收到消息通道: " + channel + " 来源类型: " + sourceType + " 数据长度: " + dataLen + " 字节");

        if (LoginSequence2VC.CHANNEL_CONNECT_OTHER.equals(channel)) {
            handleConnectOther(event);
            return;
        }

        if (LoginSequence2VC.CHANNEL_CONNECT_REQUEST.equals(channel)) {
            handleConnectRequest(event);
            return;
        }

        if (LoginSequence2VC.CHANNEL_SERVER_INFO.equals(channel)) {
            // 按消息 type 字段判断请求/响应，不再依赖来源类型
            String msgType = peekMessageType(event.getData());
            if ("RESP".equals(msgType)) {
                plugin.debug("ServerInfo: 识别为响应消息");
                handleServerInfoResponse(event);
            } else if ("REQ".equals(msgType)) {
                plugin.debug("ServerInfo: 识别为请求消息");
                handleServerInfoRequest(event);
            } else {
                plugin.debug("ServerInfo: 未知消息类型: " + msgType);
            }
            return;
        }

        if (LoginSequence2VC.CHANNEL_LOGIN_SUCCESS.equals(channel)) {
            handleLoginSuccess(event);
            return;
        }
    }

    /**
     * 处理 ConnectOther 通道消息
     * 格式: [targetPlayer] [targetServer]
     */
    private void handleConnectOther(PluginMessageEvent event) {
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

        plugin.debug("ConnectOther: 将玩家 " + targetPlayerName + " 转移到 " + targetServerName);
        target.createConnectionRequest(targetSrv).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.warn("ConnectOther: 将玩家 {} 转移到 {} 失败: {}",
                        targetPlayerName, targetServerName, throwable.getMessage());
            }
        });

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 处理玩家主动请求连接到自己当前所在服务器之外的其他子服务器
     */
    private void handleConnectRequest(PluginMessageEvent event) {
        // 从来源关联的服务器上找一个玩家来执行连接请求
        Player sender = findPlayerFromSource(event.getSource());
        if (sender == null) {
            logger.warn("ConnectRequest: 无法从来源找到玩家来执行连接");
            return;
        }

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

        plugin.debug("ConnectRequest: 玩家 " + sender.getUsername() + " 请求连接到 " + targetServerName);
        sender.createConnectionRequest(serverOpt.get()).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                logger.warn("ConnectRequest: 将玩家 {} 转移到 {} 失败: {}",
                        sender.getUsername(), targetServerName, throwable.getMessage());
            }
        });

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 从消息来源中找到一个玩家
     */
    private Player findPlayerFromSource(Object source) {
        if (source instanceof Player) {
            return (Player) source;
        } else if (source instanceof ServerConnection) {
            for (Player p : ((ServerConnection) source).getServer().getPlayersConnected()) {
                return p;
            }
        }
        // 兜底：从所有在线玩家中找一个
        for (Player p : server.getAllPlayers()) {
            return p;
        }
        return null;
    }

    /**
     * 处理 ServerInfo 请求
     * 请求格式: [type="REQ"] [serverName]
     * 策略：将请求转发给目标子服务器，由子服务器插件返回真实信息。
     */
    private void handleServerInfoRequest(PluginMessageEvent event) {
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String type;
        String serverName;
        try {
            type = in.readUTF();
            serverName = in.readUTF();
        } catch (Exception e) {
            logger.warn("ServerInfo 请求格式错误: {}", e.getMessage());
            return;
        }
        plugin.debug("ServerInfo: 收到请求查询服务器: " + serverName);

        Optional<RegisteredServer> targetOpt = server.getServer(serverName);
        if (targetOpt.isEmpty()) {
            plugin.debug("ServerInfo: 目标服务器 " + serverName + " 不存在，返回离线状态");
            // 需要找到一个玩家来发送响应
            findAnyPlayerToRespond(event.getSource(), serverName, 0, 0, false);
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }

        RegisteredServer targetServer = targetOpt.get();

        // 转发 ServerInfo 请求到目标子服务器，由该服务器上的 LS2Online 返回信息
        // 注意：必须通过目标服务器上的玩家连接发送，Bukkit 端才能收到插件消息
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("REQ");
        out.writeUTF(serverName);

        plugin.debug("ServerInfo: 转发请求到子服务器，目标: " + serverName + " 目标服在线玩家数: " + targetServer.getPlayersConnected().size());
        // 使用目标服务器上的任意玩家来转发请求
        if (!targetServer.getPlayersConnected().isEmpty()) {
            Player relayPlayer = targetServer.getPlayersConnected().iterator().next();
            plugin.debug("ServerInfo: 使用转发玩家: " + relayPlayer.getUsername());
            relayPlayer.getCurrentServer().ifPresent(conn -> {
                    plugin.debug("ServerInfo: 通过连接 " + conn.getServerInfo().getName() + " 发送转发请求");
                    conn.sendPluginMessage(
                            com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from(LoginSequence2VC.CHANNEL_SERVER_INFO),
                            out.toByteArray()
                    );
            });
        } else {
            // 目标服务器没有玩家，无法通过玩家连接转发请求
            // 返回在线状态为 true，但人数为 0，让 Main 端自行判断
            plugin.debug("ServerInfo: 目标服务器没有玩家，返回在线但人数为0");
            findAnyPlayerToRespond(event.getSource(), serverName, 0, targetServer.getPlayersConnected().size(), true);
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 向与消息来源关联的任意玩家发送响应
     */
    private void findAnyPlayerToRespond(Object source, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        if (source instanceof Player) {
            sendServerInfoResponse((Player) source, serverName, online, maxPlayers, onlineStatus);
        } else if (source instanceof ServerConnection) {
            // 从来源服务器上找一个玩家来发送响应
            ServerConnection conn = (ServerConnection) source;
            for (Player p : conn.getServer().getPlayersConnected()) {
                sendServerInfoResponse(p, serverName, online, maxPlayers, onlineStatus);
                return;
            }
        }
    }

    /**
     * 处理来自子服务器的 ServerInfo 响应
     * 响应格式: [serverName] [online] [maxPlayers] [onlineStatus]
     * 策略：将响应转发给当前在该子服务器上的所有玩家。
     */
    private void handleServerInfoResponse(PluginMessageEvent event) {
        ServerConnection sourceServer = (ServerConnection) event.getSource();
        byte[] data = event.getData();
        plugin.debug("ServerInfo: 收到来自子服务器 " + sourceServer.getServerInfo().getName() + " 的响应，数据长度=" + data.length + " 字节");

        ByteArrayDataInput in = ByteStreams.newDataInput(data);

        String type;
        String serverName;
        int online;
        int maxPlayers;
        boolean onlineStatus;
        try {
            type = in.readUTF();
            serverName = in.readUTF();
            plugin.debug("ServerInfo: 读取到 serverName=" + serverName);
            online = in.readInt();
            plugin.debug("ServerInfo: 读取到 online=" + online);
            maxPlayers = in.readInt();
            plugin.debug("ServerInfo: 读取到 maxPlayers=" + maxPlayers);
            onlineStatus = in.readBoolean();
            plugin.debug("ServerInfo: 读取到 onlineStatus=" + onlineStatus);
        } catch (Exception e) {
            logger.warn("ServerInfo 响应格式错误: {} 数据长度={} 字节 来源服务器={}",
                    e.getMessage(), data.length, sourceServer.getServerInfo().getName());
            return;
        }

        plugin.debug("ServerInfo: 响应 " + serverName + " 在线=" + online + " 最大=" + maxPlayers + " 状态=" + onlineStatus);
        // 将响应转发给所有子服务器上的所有在线玩家，确保请求者能收到
        int playerCount = 0;
        for (RegisteredServer rs : server.getAllServers()) {
            for (Player player : rs.getPlayersConnected()) {
                sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus);
                playerCount++;
            }
        }
        plugin.debug("ServerInfo: 响应已转发给 " + playerCount + " 个玩家");

        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    private void sendServerInfoResponse(Player sender, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
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

    /**
     * 处理玩家登录成功消息
     */
    private void handleLoginSuccess(PluginMessageEvent event) {
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String playerName;
        String uuidStr;
        try {
            playerName = in.readUTF();
            uuidStr = in.readUTF();
        } catch (Exception e) {
            logger.warn("LoginSuccess 消息格式错误: {}", e.getMessage());
            return;
        }

        try {
            UUID uuid = UUID.fromString(uuidStr);
            loggedInPlayers.add(uuid);
            plugin.debug("LoginSuccess: 玩家 " + playerName + " (" + uuid + ") 已登录，允许使用 /server 命令");
        } catch (IllegalArgumentException e) {
            logger.warn("LoginSuccess: 无效的 UUID: {}", uuidStr);
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    /**
     * 检查玩家是否已登录成功
     */
    public boolean isPlayerLoggedIn(UUID uuid) {
        return loggedInPlayers.contains(uuid);
    }

    /**
     * 玩家断开连接时移除登录状态
     */
    public void removePlayer(UUID uuid) {
        loggedInPlayers.remove(uuid);
        plugin.debug("LoginSuccess: 玩家 " + uuid + " 已断开，移除登录状态");
    }

    /**
     * 预览消息的第一个 UTF 字段，用于判断消息类型
     */
    private String peekMessageType(byte[] data) {
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(data);
            return in.readUTF();
        } catch (Exception e) {
            return null;
        }
    }
}
