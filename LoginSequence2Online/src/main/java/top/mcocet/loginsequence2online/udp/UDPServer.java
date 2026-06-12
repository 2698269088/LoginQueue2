package top.mcocet.loginsequence2online.udp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginsequence2online.util.CryptoUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP 服务端
 * 监听端口，响应主插件的服务器信息请求
 */
public class UDPServer {

    private static final String TYPE_KEY_REQUEST = "KEY_REQ";
    private static final String TYPE_KEY_EXCHANGE = "KEY_EXCH";
    private static final String TYPE_SERVER_INFO_REQUEST = "INFO_REQ";
    private static final String TYPE_KEY_RESPONSE = "KEY_RESP";
    private static final String TYPE_KEY_ERROR = "KEY_ERR";
    private static final String TYPE_SERVER_INFO_RESPONSE = "INFO_RESP";
    private static final String SEPARATOR = "|";

    private final JavaPlugin plugin;
    private final int port;
    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile boolean running = false;
    private String secretKey;

    public UDPServer(JavaPlugin plugin, int port) {
        this.plugin = plugin;
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
                Thread t = new Thread(r, "LS2O-UDP-Server");
                t.setDaemon(true);
                return t;
            });

            // 从配置读取密钥，若为空则等待主插件同步
            String configuredKey = plugin.getConfig().getString("udp-sync.secret-key", "");
            if (configuredKey != null && !configuredKey.isEmpty()) {
                secretKey = configuredKey;
                plugin.getLogger().info("UDP 服务端已启动，监听端口: " + port);
                plugin.getLogger().info("UDP 使用配置文件中的预设密钥。");
            } else {
                secretKey = null;
                plugin.getLogger().info("UDP 服务端已启动，监听端口: " + port);
                plugin.getLogger().info("SHA256 密钥未设置，等待主插件同步密钥...");
            }

            executor.submit(this::listen);
        } catch (SocketException e) {
            plugin.getLogger().severe("UDP 服务端启动失败: " + e.getMessage());
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
        plugin.getLogger().info("UDP 服务端已停止。");
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
                    plugin.getLogger().warning("UDP 接收数据异常: " + e.getMessage());
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String rawData;
        try {
            rawData = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("UDP 数据解析失败: " + e.getMessage());
            return;
        }

        InetAddress clientAddress = packet.getAddress();
        int clientPort = packet.getPort();

        // 解析消息类型（按分隔符分割，支持变长类型标识）
        int sepIndex = rawData.indexOf(SEPARATOR);
        if (sepIndex < 0) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("UDP 收到无效数据（无分隔符）: " + rawData.substring(0, Math.min(rawData.length(), 20)));
            }
            return;
        }

        String type = rawData.substring(0, sepIndex);
        String payload = sepIndex + 1 < rawData.length() ? rawData.substring(sepIndex + 1) : "";

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("UDP 收到消息类型: " + type + "，来自: " + clientAddress.getHostAddress() + ":" + clientPort);
        }

        switch (type) {
            case TYPE_KEY_REQUEST:
                handleKeyRequest(clientAddress, clientPort);
                break;
            case TYPE_KEY_EXCHANGE:
                handleKeyExchange(clientAddress, clientPort, payload);
                break;
            case TYPE_SERVER_INFO_REQUEST:
                handleServerInfoRequest(clientAddress, clientPort, payload);
                break;
            default:
                plugin.getLogger().warning("UDP 收到未知消息类型: " + type);
                break;
        }
    }

    /**
     * 处理密钥请求 - 如果已有密钥则返回，否则返回错误
     */
    private void handleKeyRequest(InetAddress address, int port) {
        plugin.getLogger().info("UDP 收到密钥请求，来自: " + address.getHostAddress() + ":" + port);

        String serverName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());

        if (secretKey == null || secretKey.isEmpty()) {
            // 没有密钥，返回错误
            String response = TYPE_KEY_ERROR + SEPARATOR + serverName + SEPARATOR + "NO_KEY";
            sendPacket(address, port, response);
            plugin.getLogger().warning("UDP 密钥请求失败: 当前没有配置密钥");
            return;
        }

        String response = TYPE_KEY_RESPONSE + SEPARATOR + serverName + SEPARATOR + secretKey;
        sendPacket(address, port, response);
        plugin.getLogger().info("UDP 已发送密钥响应，服务器: " + serverName);
    }

    /**
     * 处理密钥同步 - LS2 主插件将自动生成的密钥发送过来
     * 流程: LS2 发送 KEY_EXCH|encryptedSecretKey → LS2O 解密保存并返回 KEY_RESP|serverName|OK
     * 如果配置了 planned-key，则先用 planned-key 解密 payload
     */
    private void handleKeyExchange(InetAddress address, int port, String payload) {
        plugin.getLogger().info("UDP 收到密钥同步，来自: " + address.getHostAddress() + ":" + port);

        String serverName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());

        // 如果已经配置了密钥，拒绝同步（避免被覆盖）
        String configuredKey = plugin.getConfig().getString("udp-sync.secret-key", "");
        if (configuredKey != null && !configuredKey.isEmpty()) {
            String response = TYPE_KEY_ERROR + SEPARATOR + serverName + SEPARATOR + "KEY_CONFIGURED";
            sendPacket(address, port, response);
            plugin.getLogger().warning("UDP 密钥同步被拒绝: 服务端已配置固定密钥");
            return;
        }

        // 保存同步过来的密钥
        if (payload == null || payload.isEmpty()) {
            String response = TYPE_KEY_ERROR + SEPARATOR + serverName + SEPARATOR + "EMPTY_KEY";
            sendPacket(address, port, response);
            plugin.getLogger().warning("UDP 密钥同步失败: 收到的密钥为空");
            return;
        }

        // 如果配置了 planned-key，则先用 planned-key 解密 payload
        String plannedKey = plugin.getConfig().getString("udp-sync.planned-key", "");
        if (plannedKey != null && !plannedKey.isEmpty()) {
            try {
                secretKey = CryptoUtil.decryptWithStringKey(payload, plannedKey);
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("UDP 使用 planned-key 解密密钥。");
                }
            } catch (Exception e) {
                String response = TYPE_KEY_ERROR + SEPARATOR + serverName + SEPARATOR + "DECRYPT_FAILED";
                sendPacket(address, port, response);
                plugin.getLogger().warning("UDP 密钥同步失败: planned-key 解密失败，请检查两端 planned-key 是否一致");
                return;
            }
        } else {
            // 明文传输（向后兼容）
            secretKey = payload;
        }

        plugin.getLogger().info("UDP 密钥已同步，来自: " + address.getHostAddress());

        String response = TYPE_KEY_RESPONSE + SEPARATOR + serverName + SEPARATOR + "OK";
        sendPacket(address, port, response);
    }

    /**
     * 处理服务器信息请求 - 使用密钥加密响应
     */
    private void handleServerInfoRequest(InetAddress address, int port, String encryptedPayload) {
        // 检查是否有密钥
        if (secretKey == null || secretKey.isEmpty()) {
            String serverName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());
            String response = TYPE_KEY_ERROR + SEPARATOR + serverName + SEPARATOR + "NO_KEY";
            sendPacket(address, port, response);
            plugin.getLogger().warning("UDP 服务器信息请求被拒绝: 当前没有配置密钥");
            return;
        }

        // 解密请求
        String decrypted;
        try {
            decrypted = CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning("UDP 服务器信息请求解密失败: " + e.getMessage());
            return;
        }

        if (!"GET_INFO".equals(decrypted)) {
            plugin.getLogger().warning("UDP 服务器信息请求内容无效: " + decrypted);
            return;
        }

        String serverName = plugin.getConfig().getString("server-name", Bukkit.getServer().getName());
        int online = Bukkit.getServer().getOnlinePlayers().size();
        int max = Bukkit.getServer().getMaxPlayers();
        boolean onlineStatus = true;

        // 构造响应数据: serverName|online|max|onlineStatus
        String rawResponse = serverName + SEPARATOR + online + SEPARATOR + max + SEPARATOR + onlineStatus;
        String encryptedResponse = CryptoUtil.encryptWithStringKey(rawResponse, secretKey);

        String response = TYPE_SERVER_INFO_RESPONSE + SEPARATOR + encryptedResponse;
        sendPacket(address, port, response);

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("UDP 已发送服务器信息响应: " + serverName + " [" + online + "/" + max + "]");
        }
    }

    private void sendPacket(InetAddress address, int port, String data) {
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            plugin.getLogger().warning("UDP 发送数据失败: " + e.getMessage());
        }
    }
}
