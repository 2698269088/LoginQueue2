package top.mcocet.loginqueue2.udp;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.util.CryptoUtil;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP 服务端
 * 接收子服务器（LoginQueue2Online）发送的 /connect 虚拟排队请求
 */
public class UDPServer implements PlayerJoinListener.VirtualQueueHandler {

    private static final String TYPE_CONNECT_REQUEST = "CONN_REQ";
    private static final String TYPE_CONNECT_RESPONSE = "CONN_RESP";
    private static final String TYPE_QUEUE_STATUS = "QUEUE_STATUS";
    private static final String TYPE_CONNECT_ALLOW = "CONN_ALLOW";
    private static final String TYPE_CONNECT_CANCEL = "CONN_CANCEL";
    private static final String SEPARATOR = "|";

    private final LoginQueue2 plugin;
    private final LanguageManager languageManager;
    private final BungeeMessenger messenger;
    private final PlayerJoinListener playerJoinListener;
    private final int port;
    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public UDPServer(LoginQueue2 plugin, BungeeMessenger messenger, PlayerJoinListener playerJoinListener, int port) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.messenger = messenger;
        this.playerJoinListener = playerJoinListener;
        this.port = port;
    }

    /**
     * 启动 UDP 服务端
     */
    public void start() {
        if (running) {
            return;
        }
        try {
            socket = new DatagramSocket(port);
            running = true;
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LS2-UDP-Server");
                t.setDaemon(true);
                return t;
            });
            executor.submit(this::listen);

            plugin.getLogger().info(languageManager.getLogMessage("udp-server-started", "port", String.valueOf(port)));

            // 注册虚拟队列处理器
            playerJoinListener.setVirtualQueueHandler(this);

            // 启动队列状态广播任务
            long interval = plugin.getConfig().getLong("udp-sync.connect-queue.status-interval", 3) * 20L;
            if (interval > 0) {
                SchedulerUtil.runTaskTimer(plugin, this::broadcastQueueStatus, interval, interval);
            }
        } catch (SocketException e) {
            plugin.getLogger().severe(languageManager.getLogMessage("udp-server-start-failed", "port", String.valueOf(port), "error", e.getMessage()));
        }
    }

    /**
     * 停止 UDP 服务端
     */
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        plugin.getLogger().info(languageManager.getLogMessage("udp-server-stopped"));
    }

    private void listen() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running) {
                    plugin.getLogger().warning(languageManager.getLogMessage("udp-server-receive-error", "error", e.getMessage()));
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String rawData;
        try {
            rawData = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-parse-error", "error", e.getMessage()));
            return;
        }

        int sepIndex = rawData.indexOf(SEPARATOR);
        if (sepIndex < 0) {
            if (plugin.isDebug()) {
                plugin.getLogger().warning(languageManager.getLogMessage("udp-server-invalid-data", "data", rawData.substring(0, Math.min(rawData.length(), 20))));
            }
            return;
        }

        String type = rawData.substring(0, sepIndex);
        String payload = sepIndex + 1 < rawData.length() ? rawData.substring(sepIndex + 1) : "";

        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-server-received", "type", type, "ip", packet.getAddress().getHostAddress(), "port", String.valueOf(packet.getPort())));
        }

        switch (type) {
            case TYPE_CONNECT_REQUEST:
                handleConnectRequest(payload);
                break;
            case TYPE_CONNECT_CANCEL:
                handleConnectCancel(payload);
                break;
            default:
                if (plugin.isDebug()) {
                    plugin.getLogger().warning(languageManager.getLogMessage("udp-server-unknown-type", "type", type));
                }
                break;
        }
    }

    /**
     * 处理来自子服务器的连接请求
     * 格式: CONN_REQ|serverName|encryptedPayload
     */
    private void handleConnectRequest(String payload) {
        int sepIndex = payload.indexOf(SEPARATOR);
        if (sepIndex < 0) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-request-format-error"));
            return;
        }
        String serverName = payload.substring(0, sepIndex);
        String encryptedPayload = payload.substring(sepIndex + 1);

        UDPClient client = getUDPClient(serverName);
        if (client == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-client-not-found", "server", serverName));
            return;
        }

        String secretKey = client.getSecretKey();
        if (secretKey == null || secretKey.isEmpty()) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-no-key", "server", serverName));
            return;
        }

        String decrypted;
        try {
            decrypted = CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-decrypt-failed", "server", serverName, "error", e.getMessage()));
            return;
        }

        // 解析: playerUuid|playerName|targetServer|priority
        String[] parts = decrypted.split("\\|", 4);
        if (parts.length < 3) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-request-data-error", "data", decrypted));
            return;
        }

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-invalid-uuid", "uuid", parts[0]));
            return;
        }
        String playerName = parts[1];
        String targetServer = parts[2];
        int priority = 0;
        if (parts.length >= 4) {
            try {
                priority = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ignored) {
            }
        }

        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-server-connect-request",
                    "player", playerName, "uuid", playerUuid.toString(), "server", targetServer, "source", serverName));
        }

        boolean success = playerJoinListener.addVirtualPlayerToQueue(playerUuid, targetServer, serverName, priority);
        int position = playerJoinListener.getVirtualPlayerPosition(playerUuid);
        int online = 0;
        int max = 0;
        BungeeMessenger.ServerStatus status = messenger.getServerStatus(targetServer);
        if (status != null) {
            online = status.getOnlinePlayers();
            max = status.getMaxPlayers();
            if (max <= 0) {
                max = plugin.getConfig().getInt("queue.max-online", 50);
            }
        }

        String message = success ? languageManager.getLogMessage("udp-server-queued") : languageManager.getLogMessage("udp-server-queue-failed");
        sendConnectResponse(client, playerUuid, success, position, online, max, message);
    }

    /**
     * 处理取消排队请求
     * 格式: CONN_CANCEL|serverName|encryptedPayload
     * payload: playerUuid
     */
    private void handleConnectCancel(String payload) {
        int sepIndex = payload.indexOf(SEPARATOR);
        if (sepIndex < 0) {
            return;
        }
        String serverName = payload.substring(0, sepIndex);
        String encryptedPayload = payload.substring(sepIndex + 1);

        UDPClient client = getUDPClient(serverName);
        if (client == null) {
            return;
        }
        String secretKey = client.getSecretKey();
        if (secretKey == null || secretKey.isEmpty()) {
            return;
        }

        String decrypted;
        try {
            decrypted = CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            return;
        }

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(decrypted);
        } catch (IllegalArgumentException e) {
            return;
        }

        playerJoinListener.removeVirtualPlayerFromQueue(playerUuid);
        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-server-connect-cancel", "uuid", playerUuid.toString(), "source", serverName));
        }
    }

    /**
     * 发送连接请求响应
     */
    private void sendConnectResponse(UDPClient client, UUID uuid, boolean success, int position, int online, int max, String message) {
        String rawPayload = uuid.toString() + SEPARATOR + success + SEPARATOR + position + SEPARATOR + online + SEPARATOR + max + SEPARATOR + message;
        String encrypted;
        try {
            encrypted = CryptoUtil.encryptWithStringKey(rawPayload, client.getSecretKey());
        } catch (Exception e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-encrypt-failed", "server", client.getServerName(), "error", e.getMessage()));
            return;
        }
        String response = TYPE_CONNECT_RESPONSE + SEPARATOR + client.getServerName() + SEPARATOR + encrypted;
        client.sendRawData(response);
    }

    /**
     * 广播所有虚拟玩家的队列状态
     */
    private void broadcastQueueStatus() {
        Set<UUID> virtualUuids = playerJoinListener.getVirtualPlayerUuids();
        if (virtualUuids.isEmpty()) {
            return;
        }
        for (UUID uuid : virtualUuids) {
            String sourceServer = playerJoinListener.getVirtualPlayerSourceServer(uuid);
            String targetServer = playerJoinListener.getVirtualPlayerTargetServer(uuid);
            if (sourceServer == null || targetServer == null) {
                continue;
            }
            UDPClient client = getUDPClient(sourceServer);
            if (client == null) {
                continue;
            }
            String secretKey = client.getSecretKey();
            if (secretKey == null || secretKey.isEmpty()) {
                continue;
            }

            int position = playerJoinListener.getVirtualPlayerPosition(uuid);
            int online = 0;
            int max = 0;
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(targetServer);
            if (status != null) {
                online = status.getOnlinePlayers();
                max = status.getMaxPlayers();
                if (max <= 0) {
                    max = plugin.getConfig().getInt("queue.max-online", 50);
                }
            }

            String rawPayload = uuid.toString() + SEPARATOR + position + SEPARATOR + online + SEPARATOR + max;
            String encrypted;
            try {
                encrypted = CryptoUtil.encryptWithStringKey(rawPayload, secretKey);
            } catch (Exception e) {
                if (plugin.isDebug()) {
                    plugin.getLogger().warning(languageManager.getLogMessage("udp-server-status-encrypt-failed", "server", sourceServer, "error", e.getMessage()));
                }
                continue;
            }
            String response = TYPE_QUEUE_STATUS + SEPARATOR + sourceServer + SEPARATOR + encrypted;
            client.sendRawData(response);
        }
    }

    /**
     * 当虚拟玩家被放行时回调，发送 UDP 放行通知给子服务器
     */
    @Override
    public void onVirtualPlayerAllowed(UUID uuid, String targetServer, String sourceServer) {
        UDPClient client = getUDPClient(sourceServer);
        if (client == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-allow-no-client", "server", sourceServer));
            return;
        }
        String secretKey = client.getSecretKey();
        if (secretKey == null || secretKey.isEmpty()) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-allow-no-key", "server", sourceServer));
            return;
        }

        String rawPayload = uuid.toString() + SEPARATOR + targetServer;
        String encrypted;
        try {
            encrypted = CryptoUtil.encryptWithStringKey(rawPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-allow-encrypt-failed", "server", sourceServer, "error", e.getMessage()));
            return;
        }
        String response = TYPE_CONNECT_ALLOW + SEPARATOR + sourceServer + SEPARATOR + encrypted;
        client.sendRawData(response);

        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("udp-server-player-allowed", "uuid", uuid.toString(), "server", targetServer, "source", sourceServer));
        }
    }

    /**
     * 根据服务器名称获取 UDP 客户端
     */
    private UDPClient getUDPClient(String serverName) {
        for (UDPClient client : messenger.getUdpClients()) {
            if (client.getServerName().equals(serverName)) {
                return client;
            }
        }
        return null;
    }
}
