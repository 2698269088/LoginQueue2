package top.mcocet.loginsequence2bc.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import top.mcocet.loginsequence2bc.LoginSequence2BC;

public class PluginMessageListener implements Listener {

    private final LoginSequence2BC plugin;
    private final ProxyServer proxy;

    public PluginMessageListener(LoginSequence2BC plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getProxy();
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getTag();
        String senderType = event.getSender().getClass().getSimpleName();
        int dataLen = event.getData().length;
        plugin.debug("收到消息通道: " + channel + " 发送者类型: " + senderType + " 数据长度: " + dataLen + " 字节");

        if (LoginSequence2BC.CHANNEL_CONNECT_OTHER.equals(channel)) {
            handleConnectOther(event);
            return;
        }

        if (LoginSequence2BC.CHANNEL_CONNECT_REQUEST.equals(channel)) {
            handleConnectRequest(event);
            return;
        }

        if (LoginSequence2BC.CHANNEL_SERVER_INFO.equals(channel)) {
            // 按消息 type 字段判断请求/响应，不再依赖发送者类型
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
        }
    }

    private void handleConnectOther(PluginMessageEvent event) {
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String targetPlayerName;
        String targetServerName;
        try {
            targetPlayerName = in.readUTF();
            targetServerName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ConnectOther 消息格式错误: " + e.getMessage());
            return;
        }

        ProxiedPlayer target = proxy.getPlayer(targetPlayerName);
        if (target == null) {
            plugin.getLogger().warning("ConnectOther: 玩家 " + targetPlayerName + " 不在线");
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(targetServerName);
        if (targetServer == null) {
            plugin.getLogger().warning("ConnectOther: 目标服务器 " + targetServerName + " 不存在");
            return;
        }

        plugin.debug("ConnectOther: 将玩家 " + targetPlayerName + " 转移到 " + targetServerName);
        target.connect(targetServer);
        event.setCancelled(true);
    }

    /**
     * 处理玩家主动请求连接到自己当前所在服务器之外的其他子服务器
     */
    private void handleConnectRequest(PluginMessageEvent event) {
        // 从来源关联的服务器上找一个玩家来执行连接请求
        ProxiedPlayer sender = findPlayerFromSender(event.getSender());
        if (sender == null) {
            plugin.getLogger().warning("ConnectRequest: 无法从来源找到玩家来执行连接");
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String targetServerName;
        try {
            targetServerName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ConnectRequest 消息格式错误: " + e.getMessage());
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(targetServerName);
        if (targetServer == null) {
            plugin.getLogger().warning("ConnectRequest: 目标服务器 " + targetServerName + " 不存在");
            return;
        }

        plugin.debug("ConnectRequest: 玩家 " + sender.getName() + " 请求连接到 " + targetServerName);
        sender.connect(targetServer);
        event.setCancelled(true);
    }

    /**
     * 从消息发送者中找到一个玩家
     */
    private ProxiedPlayer findPlayerFromSender(Object sender) {
        if (sender instanceof ProxiedPlayer) {
            return (ProxiedPlayer) sender;
        } else if (sender instanceof Server) {
            for (ProxiedPlayer p : ((Server) sender).getInfo().getPlayers()) {
                return p;
            }
        }
        // 兜底：从所有在线玩家中找一个
        for (ProxiedPlayer p : proxy.getPlayers()) {
            return p;
        }
        return null;
    }

    private void handleServerInfoRequest(PluginMessageEvent event) {
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String type;
        String serverName;
        try {
            type = in.readUTF();
            serverName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ServerInfo 请求格式错误: " + e.getMessage());
            return;
        }
        plugin.debug("ServerInfo: 收到请求查询服务器: " + serverName);

        ServerInfo targetServer = proxy.getServerInfo(serverName);
        if (targetServer == null) {
            plugin.debug("ServerInfo: 目标服务器 " + serverName + " 不存在，返回离线状态");
            findAnyPlayerToRespond(event.getSender(), serverName, 0, 0, false);
            event.setCancelled(true);
            return;
        }

        plugin.debug("ServerInfo: 转发请求到子服务器，目标: " + serverName + " 目标服在线玩家数: " + targetServer.getPlayers().size());
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("REQ");
        out.writeUTF(serverName);
        // 注意：必须通过目标服务器上的玩家连接发送，Bukkit 端才能收到插件消息
        if (!targetServer.getPlayers().isEmpty()) {
            ProxiedPlayer relayPlayer = targetServer.getPlayers().iterator().next();
            plugin.debug("ServerInfo: 使用转发玩家: " + relayPlayer.getName());
            relayPlayer.sendData(LoginSequence2BC.CHANNEL_SERVER_INFO, out.toByteArray());
        } else {
            // 目标服务器没有玩家，无法通过玩家连接转发请求
            // 返回在线状态为 true，但人数为 0，让 Main 端自行判断
            plugin.debug("ServerInfo: 目标服务器没有玩家，返回在线但人数为0");
            findAnyPlayerToRespond(event.getSender(), serverName, 0, targetServer.getPlayers().size(), true);
        }
        event.setCancelled(true);
    }

    /**
     * 向与消息来源关联的任意玩家发送响应
     */
    private void findAnyPlayerToRespond(Object sender, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        if (sender instanceof ProxiedPlayer) {
            sendServerInfoResponse((ProxiedPlayer) sender, serverName, online, maxPlayers, onlineStatus);
        } else if (sender instanceof Server) {
            // 从来源服务器上找一个玩家来发送响应
            Server server = (Server) sender;
            for (ProxiedPlayer p : server.getInfo().getPlayers()) {
                sendServerInfoResponse(p, serverName, online, maxPlayers, onlineStatus);
                return;
            }
        }
    }

    private void handleServerInfoResponse(PluginMessageEvent event) {
        Server sourceServer = (Server) event.getSender();
        byte[] data = event.getData();
        plugin.debug("ServerInfo: 收到来自子服务器 " + sourceServer.getInfo().getName() + " 的响应，数据长度=" + data.length + " 字节");

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
            plugin.getLogger().warning("ServerInfo 响应格式错误: " + e.getMessage() + " 数据长度=" + data.length + " 字节 来源服务器=" + sourceServer.getInfo().getName());
            return;
        }

        plugin.debug("ServerInfo: 响应 " + serverName + " 在线=" + online + " 最大=" + maxPlayers + " 状态=" + onlineStatus);
        // 将响应转发给所有子服务器上的所有在线玩家，确保请求者能收到
        int playerCount = 0;
        for (net.md_5.bungee.api.config.ServerInfo si : proxy.getServers().values()) {
            for (ProxiedPlayer player : si.getPlayers()) {
                sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus);
                playerCount++;
            }
        }
        plugin.debug("ServerInfo: 响应已转发给 " + playerCount + " 个玩家");
        event.setCancelled(true);
    }

    private void sendServerInfoResponse(ProxiedPlayer player, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(maxPlayers);
        out.writeBoolean(onlineStatus);

        player.sendData(LoginSequence2BC.CHANNEL_SERVER_INFO, out.toByteArray());
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
