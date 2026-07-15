package top.mcocet.loginqueue2bc.listener;

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
import top.mcocet.loginqueue2bc.LoginQueue2BC;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PluginMessageListener implements Listener {

    private final LoginQueue2BC plugin;
    private final ProxyServer proxy;

    // 记录已登录成功的玩家（用于限制 /server 命令）
    private final Set<UUID> loggedInPlayers = new HashSet<>();

    public PluginMessageListener(LoginQueue2BC plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getProxy();
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getTag();
        String senderType = event.getSender().getClass().getSimpleName();
        int dataLen = event.getData().length;
        plugin.debug(plugin.getLanguageManager().getLogMessage("plugin-message-received", "channel", channel, "senderType", senderType, "dataLen", String.valueOf(dataLen)));

        if (LoginQueue2BC.CHANNEL_CONNECT_OTHER.equals(channel)) {
            handleConnectOther(event);
            return;
        }

        if (LoginQueue2BC.CHANNEL_CONNECT_REQUEST.equals(channel)) {
            handleConnectRequest(event);
            return;
        }

        if (LoginQueue2BC.CHANNEL_SERVER_INFO.equals(channel)) {
            // 按消息 type 字段判断请求/响应，不再依赖发送者类型
            String msgType = peekMessageType(event.getData());
            if ("RESP".equals(msgType)) {
                plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-identified-response"));
                handleServerInfoResponse(event);
            } else if ("REQ".equals(msgType)) {
                plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-identified-request"));
                handleServerInfoRequest(event);
            } else {
                plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-unknown-type", "type", msgType));
            }
        }

        if (LoginQueue2BC.CHANNEL_LOGIN_SUCCESS.equals(channel)) {
            handleLoginSuccess(event);
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
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-other-format-error", "error", e.getMessage()));
            return;
        }

        ProxiedPlayer target = proxy.getPlayer(targetPlayerName);
        if (target == null) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-other-player-offline", "player", targetPlayerName));
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(targetServerName);
        if (targetServer == null) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-other-server-not-found", "server", targetServerName));
            return;
        }

        plugin.debug(plugin.getLanguageManager().getLogMessage("connect-other-redirect", "player", targetPlayerName, "server", targetServerName));
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
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-request-no-player"));
            return;
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());
        String targetServerName;
        try {
            targetServerName = in.readUTF();
        } catch (Exception e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-request-format-error", "error", e.getMessage()));
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(targetServerName);
        if (targetServer == null) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("connect-request-server-not-found", "server", targetServerName));
            return;
        }

        plugin.debug(plugin.getLanguageManager().getLogMessage("connect-request-redirect", "player", sender.getName(), "server", targetServerName));
        sender.connect(targetServer);
        event.setCancelled(true);
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
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("login-success-format-error", "error", e.getMessage()));
            return;
        }

        try {
            UUID uuid = UUID.fromString(uuidStr);
            loggedInPlayers.add(uuid);
            plugin.debug(plugin.getLanguageManager().getLogMessage("login-success-recorded", "player", playerName, "uuid", uuid.toString()));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("login-success-invalid-uuid", "uuid", uuidStr));
        }
        event.setCancelled(true);
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
        plugin.debug(plugin.getLanguageManager().getLogMessage("login-success-disconnect", "uuid", uuid.toString()));
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
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("serverinfo-request-format-error", "error", e.getMessage()));
            return;
        }
        plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-request-received", "server", serverName));

        // 检查是否是版本查询请求
        if ("VERSION_CHECK".equals(serverName)) {
            String bcProtocolVersion = LoginQueue2BC.PROTOCOL_VERSION;
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-version-check", "version", bcProtocolVersion));
            findAnyPlayerToRespond(event.getSender(), "VERSION_CHECK", 0, 0, true, 0.0, 0, 0, bcProtocolVersion);
            event.setCancelled(true);
            return;
        }

        ServerInfo targetServer = proxy.getServerInfo(serverName);
        if (targetServer == null) {
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-server-not-found", "server", serverName));
            findAnyPlayerToRespond(event.getSender(), serverName, 0, 0, false, 0.0, 0, 0);
            event.setCancelled(true);
            return;
        }

        plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-forward", "server", serverName, "count", String.valueOf(targetServer.getPlayers().size())));
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("REQ");
        out.writeUTF(serverName);
        // 注意：必须通过目标服务器上的玩家连接发送，Bukkit 端才能收到插件消息
        if (!targetServer.getPlayers().isEmpty()) {
            ProxiedPlayer relayPlayer = targetServer.getPlayers().iterator().next();
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-relay-player", "player", relayPlayer.getName()));
            relayPlayer.sendData(LoginQueue2BC.CHANNEL_SERVER_INFO, out.toByteArray());
        } else {
            // 目标服务器没有玩家，无法通过玩家连接转发请求
            // 返回在线状态为 true，但人数为 0，让 Main 端自行判断
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-no-players"));
            findAnyPlayerToRespond(event.getSender(), serverName, 0, targetServer.getPlayers().size(), true, 0.0, 0, 0);
        }
        event.setCancelled(true);
    }

    /**
     * 向与消息来源关联的任意玩家发送响应
     */
    private void findAnyPlayerToRespond(Object sender, String serverName, int online, int maxPlayers, boolean onlineStatus, double tps, long usedMemory, long maxMemory, String version) {
        if (sender instanceof ProxiedPlayer) {
            sendServerInfoResponse((ProxiedPlayer) sender, serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory, version);
        } else if (sender instanceof Server) {
            Server server = (Server) sender;
            for (ProxiedPlayer p : server.getInfo().getPlayers()) {
                sendServerInfoResponse(p, serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory, version);
                return;
            }
        }
    }

    private void findAnyPlayerToRespond(Object sender, String serverName, int online, int maxPlayers, boolean onlineStatus, double tps, long usedMemory, long maxMemory) {
        findAnyPlayerToRespond(sender, serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory, null);
    }

    private void handleServerInfoResponse(PluginMessageEvent event) {
        Server sourceServer = (Server) event.getSender();
        byte[] data = event.getData();
        plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-response-received", "server", sourceServer.getInfo().getName(), "dataLen", String.valueOf(data.length)));

        ByteArrayDataInput in = ByteStreams.newDataInput(data);

        String type;
        String serverName;
        int online;
        int maxPlayers;
        boolean onlineStatus;
        double tps = 20.0;
        long usedMemory = 0;
        long maxMemory = 0;
        try {
            type = in.readUTF();
            serverName = in.readUTF();
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-server", "server", serverName));
            online = in.readInt();
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-online", "online", String.valueOf(online)));
            maxPlayers = in.readInt();
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-max", "max", String.valueOf(maxPlayers)));
            onlineStatus = in.readBoolean();
            plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-status", "status", String.valueOf(onlineStatus)));
            // 尝试读取扩展字段（TPS、内存）
            try {
                tps = in.readDouble();
                usedMemory = in.readLong();
                maxMemory = in.readLong();
                plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-tps", "tps", String.valueOf(tps), "usedMemory", String.valueOf(usedMemory), "maxMemory", String.valueOf(maxMemory)));
            } catch (Exception e) {
                // 旧版本插件没有这些字段，使用默认值
                plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-read-ext-failed"));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("serverinfo-response-format-error", "error", e.getMessage(), "dataLen", String.valueOf(data.length), "source", sourceServer.getInfo().getName()));
            return;
        }

        plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-response-data", "server", serverName, "online", String.valueOf(online), "max", String.valueOf(maxPlayers), "status", String.valueOf(onlineStatus), "tps", String.valueOf(tps)));
        // 将响应转发给所有子服务器上的所有在线玩家，确保请求者能收到
        int playerCount = 0;
        for (net.md_5.bungee.api.config.ServerInfo si : proxy.getServers().values()) {
            for (ProxiedPlayer player : si.getPlayers()) {
                sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory);
                playerCount++;
            }
        }
        plugin.debug(plugin.getLanguageManager().getLogMessage("serverinfo-response-forwarded", "count", String.valueOf(playerCount)));
        event.setCancelled(true);
    }

    private void sendServerInfoResponse(ProxiedPlayer player, String serverName, int online, int maxPlayers, boolean onlineStatus, double tps, long usedMemory, long maxMemory, String version) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("RESP");
        out.writeUTF(serverName);
        out.writeInt(online);
        out.writeInt(maxPlayers);
        out.writeBoolean(onlineStatus);
        out.writeDouble(tps);
        out.writeLong(usedMemory);
        out.writeLong(maxMemory);
        if (version != null) {
            out.writeUTF(version);
        }

        player.sendData(LoginQueue2BC.CHANNEL_SERVER_INFO, out.toByteArray());
    }

    private void sendServerInfoResponse(ProxiedPlayer player, String serverName, int online, int maxPlayers, boolean onlineStatus, double tps, long usedMemory, long maxMemory) {
        sendServerInfoResponse(player, serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory, null);
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
