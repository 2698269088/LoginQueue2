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

        if (LoginSequence2BC.CHANNEL_CONNECT_OTHER.equals(channel)) {
            handleConnectOther(event);
            return;
        }

        if (LoginSequence2BC.CHANNEL_CONNECT_REQUEST.equals(channel)) {
            handleConnectRequest(event);
            return;
        }

        if (LoginSequence2BC.CHANNEL_SERVER_INFO.equals(channel)) {
            if (event.getSender() instanceof ProxiedPlayer) {
                handleServerInfoRequest(event);
            } else if (event.getSender() instanceof Server) {
                handleServerInfoResponse(event);
            }
        }
    }

    private void handleConnectOther(PluginMessageEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }

        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
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

        target.connect(targetServer);
        event.setCancelled(true);
    }

    /**
     * 处理玩家主动请求连接到自己当前所在服务器之外的其他子服务器
     */
    private void handleConnectRequest(PluginMessageEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }

        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
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

        sender.connect(targetServer);
        event.setCancelled(true);
    }

    private void handleServerInfoRequest(PluginMessageEvent event) {
        ProxiedPlayer sender = (ProxiedPlayer) event.getSender();
        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String serverName;
        try {
            serverName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning("ServerInfo 请求格式错误: " + e.getMessage());
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(serverName);
        if (targetServer == null) {
            sendServerInfoResponse(sender, serverName, 0, 0, false);
            event.setCancelled(true);
            return;
        }

        Server currentServer = sender.getServer();
        if (currentServer != null && currentServer.getInfo().getName().equals(serverName)) {
            return;
        }

        if (currentServer == null) {
            return;
        }

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(serverName);
        currentServer.sendData(LoginSequence2BC.CHANNEL_SERVER_INFO, out.toByteArray());
        event.setCancelled(true);
    }

    private void handleServerInfoResponse(PluginMessageEvent event) {
        Server sourceServer = (Server) event.getSender();
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
            plugin.getLogger().warning("ServerInfo 响应格式错误: " + e.getMessage());
            return;
        }

        for (ProxiedPlayer player : sourceServer.getInfo().getPlayers()) {
            sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus);
        }
        event.setCancelled(true);
    }

    private void sendServerInfoResponse(ProxiedPlayer player, String serverName, int online, int maxPlayers, boolean onlineStatus) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(maxPlayers);
        out.writeBoolean(onlineStatus);

        player.sendData(LoginSequence2BC.CHANNEL_SERVER_INFO, out.toByteArray());
    }
}
