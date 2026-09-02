package top.mcocet.loginqueue2online.udp;

import top.mcocet.loginqueue2online.LoginQueue2Online;
import top.mcocet.loginqueue2online.util.CryptoUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP 客户端
 * 向 LQ2/LQ2Limbo 主插件发送 /connect 虚拟排队请求
 */
public class UDPClient {

    private static final String TYPE_CONNECT_REQUEST = "CONN_REQ";
    private static final String TYPE_CONNECT_CANCEL = "CONN_CANCEL";
    private static final String SEPARATOR = "|";

    private final LoginQueue2Online plugin;
    private final String serverName;
    private final String host;
    private final int port;
    private final String secretKey;
    private final int timeout;
    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile boolean initialized = false;

    public UDPClient(LoginQueue2Online plugin, String serverName, String host, int port, String secretKey, int timeout) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.host = host;
        this.port = port;
        this.secretKey = secretKey;
        this.timeout = timeout;
    }

    public boolean init() {
        if (initialized) {
            return true;
        }
        if (secretKey == null || secretKey.isEmpty()) {
            plugin.getLogger().warning("UDP 主插件客户端未配置 secret-key，无法发送 /connect 请求。");
            return false;
        }

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LS2O-UDP-Client-" + serverName);
                t.setDaemon(true);
                return t;
            });
            initialized = true;
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("UDP 主插件客户端已初始化: " + serverName + " @ " + host + ":" + port);
            }
            return true;
        } catch (SocketException e) {
            plugin.getLogger().warning("UDP 主插件客户端初始化失败: " + e.getMessage());
            return false;
        }
    }

    public void shutdown() {
        initialized = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 异步发送 /connect 虚拟排队请求
     *
     * @param playerUuid   玩家 UUID
     * @param playerName   玩家名称
     * @param targetServer 目标服务器
     * @param priority     优先级
     * @return CompletableFuture，成功返回 true
     */
    public CompletableFuture<Boolean> sendConnectRequest(UUID playerUuid, String playerName, String targetServer, int priority) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (executor == null || !initialized) {
            future.complete(false);
            return future;
        }
        executor.submit(() -> {
            try {
                String rawPayload = playerUuid.toString() + SEPARATOR + playerName + SEPARATOR + targetServer + SEPARATOR + priority;
                String encrypted = CryptoUtil.encryptWithStringKey(rawPayload, secretKey);
                String request = TYPE_CONNECT_REQUEST + SEPARATOR + serverName + SEPARATOR + encrypted;
                sendPacket(request);
                future.complete(true);
            } catch (Exception e) {
                plugin.getLogger().warning("发送 /connect UDP 请求失败: " + e.getMessage());
                future.complete(false);
            }
        });
        return future;
    }

    /**
     * 异步发送取消排队请求
     *
     * @param playerUuid 玩家 UUID
     * @return CompletableFuture，成功返回 true
     */
    public CompletableFuture<Boolean> sendCancelRequest(UUID playerUuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (executor == null || !initialized) {
            future.complete(false);
            return future;
        }
        executor.submit(() -> {
            try {
                String rawPayload = playerUuid.toString();
                String encrypted = CryptoUtil.encryptWithStringKey(rawPayload, secretKey);
                String request = TYPE_CONNECT_CANCEL + SEPARATOR + serverName + SEPARATOR + encrypted;
                sendPacket(request);
                future.complete(true);
            } catch (Exception e) {
                plugin.getLogger().warning("发送取消排队 UDP 请求失败: " + e.getMessage());
                future.complete(false);
            }
        });
        return future;
    }

    private void sendPacket(String data) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(packet);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getServerName() {
        return serverName;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
