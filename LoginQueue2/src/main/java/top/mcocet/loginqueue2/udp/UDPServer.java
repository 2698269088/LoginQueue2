package top.mcocet.loginqueue2.udp;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.match.MinigameMatchManager;
import top.mcocet.loginqueue2.util.CryptoUtil;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String TYPE_SERVER_LIST = "SERVER_LIST";
    private static final String TYPE_MATCH_REPORT = "MATCH_REPORT";
    private static final String TYPE_MATCH_QUEUE_QUERY = "MATCH_QUEUE_QUERY";
    private static final String TYPE_MATCH_QUEUE_INFO = "MATCH_QUEUE_INFO";
    private static final String TYPE_MATCH_RELEASE_REQ = "MATCH_RELEASE_REQ";
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

            // 启动服务器列表与队列状态广播任务
            long interval = plugin.getConfig().getLong("udp-sync.connect-queue.status-interval", 3) * 20L;
            if (interval > 0) {
                SchedulerUtil.runTaskTimer(plugin, () -> {
                    broadcastServerList();
                    broadcastQueueStatus();
                }, interval, interval);
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
            case TYPE_MATCH_REPORT:
                handleMatchReport(payload);
                break;
            case TYPE_MATCH_QUEUE_QUERY:
                handleMatchQueueQuery(payload);
                break;
            case TYPE_MATCH_RELEASE_REQ:
                handleMatchReleaseRequest(payload);
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
     * 处理子服务器上报的对局状态（MINIGAME 模式）
     * 格式: MATCH_REPORT|serverName|encryptedPayload
     * payload: count|matchId,state,players,min,max,createdAt|...
     */
    private void handleMatchReport(String payload) {
        MinigameMatchManager matchManager = plugin.getMinigameMatchManager();
        if (matchManager == null) {
            return;
        }
        int sepIndex = payload.indexOf(SEPARATOR);
        if (sepIndex < 0) {
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

        // UDP 线程中不能直接操作 Bukkit API，调度回主线程处理
        SchedulerUtil.runTask(plugin, () -> {
            matchManager.handleMatchReport(serverName, decrypted);
            playerJoinListener.processQueueNow();
        });
    }

    /**
     * 处理子服的排队队列查询请求（MATCH_QUEUE_QUERY）
     * 格式: MATCH_QUEUE_QUERY|serverName|encryptedPayload
     * payload: QUERY
     * 响应: MATCH_QUEUE_INFO|serverName|encryptedPayload，payload: queueSize|uuid1,uuid2,...
     */
    private void handleMatchQueueQuery(String payload) {
        int sepIndex = payload.indexOf(SEPARATOR);
        if (sepIndex < 0) {
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
        try {
            CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-decrypt-failed", "server", serverName, "error", e.getMessage()));
            return;
        }

        // UDP 线程中不能直接操作 Bukkit API，调度回主线程构建队列快照后响应
        SchedulerUtil.runTask(plugin, () -> {
            String[] snapshot = playerJoinListener.buildQueueSnapshot();
            String rawPayload = snapshot[0] + SEPARATOR + snapshot[1];
            String encrypted;
            try {
                encrypted = CryptoUtil.encryptWithStringKey(rawPayload, client.getSecretKey());
            } catch (Exception e) {
                plugin.getLogger().warning(languageManager.getLogMessage("udp-server-encrypt-failed", "server", serverName, "error", e.getMessage()));
                return;
            }
            client.sendRawData(TYPE_MATCH_QUEUE_INFO + SEPARATOR + serverName + SEPARATOR + encrypted);
        });
    }

    /**
     * 处理子服的指定玩家放行请求（MATCH_RELEASE_REQ）
     * 格式: MATCH_RELEASE_REQ|serverName|encryptedPayload
     * payload: playerUuid|matchId
     */
    private void handleMatchReleaseRequest(String payload) {
        int sepIndex = payload.indexOf(SEPARATOR);
        if (sepIndex < 0) {
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

        String[] parts = decrypted.split("\\|", 2);
        if (parts.length < 2) {
            return;
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(languageManager.getLogMessage("udp-server-invalid-uuid", "uuid", parts[0]));
            return;
        }
        String matchId = parts[1];

        // UDP 线程中不能直接操作 Bukkit API，调度回主线程放行
        SchedulerUtil.runTask(plugin, () -> {
            boolean success = playerJoinListener.releaseSpecificPlayerToMatch(playerUuid, serverName, matchId);
            if (!success) {
                plugin.getLogger().warning(languageManager.getLogMessage("minigame-release-request-failed",
                        "player", playerUuid.toString(), "match", matchId, "server", serverName));
            }
        });
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
     * 向所有子服务器广播目标服务器列表（内部名 + 显示名）
     * 供子服 /connect 指令 Tab 补全与服务器存在性校验
     * 格式: SERVER_LIST|serverName|encryptedPayload
     * payload: count|name1|displayName1|name2|displayName2|...
     */
    private void broadcastServerList() {
        List<Map<?, ?>> servers = plugin.getConfig().getMapList("udp-sync.servers");
        List<String[]> namePairs = new ArrayList<>();
        if (servers != null && !servers.isEmpty()) {
            for (Map<?, ?> map : servers) {
                Object nameObj = map.get("name");
                if (nameObj == null || String.valueOf(nameObj).isEmpty()) {
                    continue;
                }
                String name = String.valueOf(nameObj);
                Object displayObj = map.get("display-name");
                String displayName = displayObj != null && !String.valueOf(displayObj).trim().isEmpty()
                        ? String.valueOf(displayObj) : name;
                namePairs.add(new String[]{name, displayName});
            }
        } else {
            // 兼容旧版单服务器配置
            String mainServer = plugin.getConfig().getString("queue.main-server", "main");
            namePairs.add(new String[]{mainServer, mainServer});
        }
        if (namePairs.isEmpty()) {
            return;
        }

        StringBuilder payload = new StringBuilder(String.valueOf(namePairs.size()));
        for (String[] pair : namePairs) {
            payload.append(SEPARATOR).append(pair[0]).append(SEPARATOR).append(pair[1]);
        }

        for (UDPClient client : messenger.getUdpClients()) {
            String secretKey = client.getSecretKey();
            if (secretKey == null || secretKey.isEmpty()) {
                continue;
            }
            String encrypted;
            try {
                encrypted = CryptoUtil.encryptWithStringKey(payload.toString(), secretKey);
            } catch (Exception e) {
                if (plugin.isDebug()) {
                    plugin.getLogger().warning("UDP 服务器列表加密失败 (" + client.getServerName() + "): " + e.getMessage());
                }
                continue;
            }
            client.sendRawData(TYPE_SERVER_LIST + SEPARATOR + client.getServerName() + SEPARATOR + encrypted);
        }
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
