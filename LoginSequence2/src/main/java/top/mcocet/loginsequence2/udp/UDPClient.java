package top.mcocet.loginsequence2.udp;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginsequence2.bungee.BungeeMessenger;
import top.mcocet.loginsequence2.util.CryptoUtil;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP 客户端
 * 向 LS2O 发送服务器信息请求
 */
public class UDPClient {

    private static final String TYPE_KEY_REQUEST = "KEY_REQ";
    private static final String TYPE_KEY_EXCHANGE = "KEY_EXCH";
    private static final String TYPE_SERVER_INFO_REQUEST = "INFO_REQ";
    private static final String TYPE_KEY_RESPONSE = "KEY_RESP";
    private static final String TYPE_KEY_ERROR = "KEY_ERR";
    private static final String TYPE_SERVER_INFO_RESPONSE = "INFO_RESP";
    private static final char SEPARATOR_CHAR = '|';

    private final JavaPlugin plugin;
    private final String serverName;
    private final String host;
    private final int port;
    private final int timeout;
    private final String configuredKey;
    private final String plannedKey;
    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile boolean initialized = false;
    private String secretKey;
    private final ConcurrentHashMap<String, BungeeMessenger.ServerStatus> statusCache = new ConcurrentHashMap<>();

    public UDPClient(JavaPlugin plugin, String serverName, String host, int port, int timeout, String configuredKey, String plannedKey) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.configuredKey = configuredKey;
        this.plannedKey = plannedKey;
    }

    /**
     * 初始化 UDP 客户端
     *
     * @return 是否初始化成功
     */
    private boolean isDebug() {
        return plugin.getConfig().getBoolean("debug", false);
    }

    public boolean init() {
        if (initialized) {
            if (isDebug()) {
                plugin.getLogger().info("UDP 客户端 [" + serverName + "] 已初始化，跳过重复初始化。");
            }
            return true;
        }

        // 清理旧的资源，防止线程泄漏
        closeSocket();
        shutdownExecutor();

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LS2-UDP-Client-" + serverName);
                t.setDaemon(true);
                return t;
            });

            plugin.getLogger().info("UDP 客户端 [" + serverName + "] 已初始化，目标: " + host + ":" + port);
            if (isDebug()) {
                plugin.getLogger().info("UDP [" + serverName + "] 超时设置: " + timeout + "ms");
            }

            // 如果配置了密钥，直接使用；否则自动生成密钥并同步给服务端
            if (configuredKey != null && !configuredKey.isEmpty()) {
                secretKey = configuredKey;
                plugin.getLogger().info("UDP [" + serverName + "] 使用配置文件中的预设密钥。");
                if (isDebug()) {
                    plugin.getLogger().info("UDP [" + serverName + "] 密钥长度: " + configuredKey.length() + " 字符");
                }
            } else {
                // 自动生成密钥，并尝试同步给服务端
                secretKey = CryptoUtil.generateRandomKey();
                plugin.getLogger().info("UDP [" + serverName + "] 密钥已自动生成，正在同步给服务端...");
                if (isDebug()) {
                    plugin.getLogger().info("UDP [" + serverName + "] 自动生成的密钥长度: " + secretKey.length() + " 字符");
                    if (plannedKey != null && !plannedKey.isEmpty()) {
                        plugin.getLogger().info("UDP [" + serverName + "] 将使用 planned-key 加密传输密钥。");
                    } else {
                        plugin.getLogger().info("UDP [" + serverName + "] 未配置 planned-key，将以明文传输密钥（向后兼容）。");
                    }
                }
                if (!exchangeKey()) {
                    plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步失败，无法使用 UDP 同步");
                    closeSocket();
                    return false;
                }
                plugin.getLogger().info("UDP [" + serverName + "] 密钥已同步给服务端。");
            }

            initialized = true;
            plugin.getLogger().info("UDP 客户端 [" + serverName + "] 初始化完成。");
            return true;
        } catch (SocketException e) {
            plugin.getLogger().severe("UDP 客户端 [" + serverName + "] 初始化失败: " + e.getMessage());
            if (isDebug()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * 关闭 UDP 客户端
     */
    public void shutdown() {
        initialized = false;
        closeSocket();
        shutdownExecutor();
        plugin.getLogger().info("UDP 客户端 [" + serverName + "] 已关闭。");
    }

    private void closeSocket() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = null;
    }

    private void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
        executor = null;
    }

    /**
     * 将自动生成的密钥同步给服务端
     * 流程: LS2 发送 KEY_EXCH|encryptedSecretKey → LS2O 解密保存并返回 KEY_RESP|serverName|OK
     * 如果配置了 planned-key，则先用 planned-key 加密 secretKey 再传输
     */
    private boolean exchangeKey() {
        String keyToSend;
        if (plannedKey != null && !plannedKey.isEmpty()) {
            // 使用 planned-key 加密 secretKey
            keyToSend = CryptoUtil.encryptWithStringKey(secretKey, plannedKey);
            if (isDebug()) {
                plugin.getLogger().info("UDP [" + serverName + "] 使用 planned-key 加密传输密钥。");
            }
        } else {
            // 明文传输（向后兼容）
            keyToSend = secretKey;
        }

        String request = TYPE_KEY_EXCHANGE + SEPARATOR_CHAR + keyToSend;
        if (isDebug()) {
            plugin.getLogger().info("UDP [" + serverName + "] 发送密钥同步请求: " + TYPE_KEY_EXCHANGE + "|***");
        }
        String response = sendAndReceiveSync(request);

        if (response == null) {
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步无响应（超时或通信异常）。");
            }
            return false;
        }

        if (isDebug()) {
            plugin.getLogger().info("UDP [" + serverName + "] 收到密钥同步响应: " + response.substring(0, Math.min(response.length(), 50)) + "...");
        }

        // 服务端返回 KEY_ERR 表示没有密钥且不接受同步（旧版本兼容）
        if (response.startsWith(TYPE_KEY_ERROR)) {
            plugin.getLogger().warning("UDP [" + serverName + "] 服务端拒绝密钥同步: " + response.substring(TYPE_KEY_ERROR.length() + 1));
            return false;
        }

        // 解析响应: KEY_RESP|serverName|OK
        if (!response.startsWith(TYPE_KEY_RESPONSE)) {
            plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步响应格式错误");
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 预期 " + TYPE_KEY_RESPONSE + "，实际收到: " + response.substring(0, Math.min(response.length(), 20)));
            }
            return false;
        }
        String payload = response.substring(TYPE_KEY_RESPONSE.length() + 1);
        int firstSep = payload.indexOf(SEPARATOR_CHAR);
        if (firstSep < 0) {
            plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步响应格式错误: 缺少分隔符");
            return false;
        }
        String serverName = payload.substring(0, firstSep);
        String result = payload.substring(firstSep + 1);
        if (!"OK".equals(result)) {
            plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步失败，服务端返回: " + result);
            return false;
        }
        plugin.getLogger().info("UDP [" + serverName + "] 密钥已同步到服务端");
        return true;
    }

    /**
     * 请求服务器信息
     * 如果服务端返回 KEY_ERR|NO_KEY，则自动同步密钥后重试
     *
     * @return 服务器状态，失败返回 null
     */
    public BungeeMessenger.ServerStatus requestServerInfo() {
        if (!initialized || secretKey == null) {
            if (!init()) {
                return null;
            }
        }

        return doRequestServerInfo(false);
    }

    /**
     * 实际请求服务器信息
     *
     * @param isRetry 是否是重试（避免无限递归）
     * @return 服务器状态，失败返回 null
     */
    private BungeeMessenger.ServerStatus doRequestServerInfo(boolean isRetry) {
        // 加密请求内容
        String encryptedRequest;
        try {
            encryptedRequest = CryptoUtil.encryptWithStringKey("GET_INFO", secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning("UDP 请求加密失败: " + e.getMessage());
            if (isDebug()) {
                e.printStackTrace();
            }
            return null;
        }

        String request = TYPE_SERVER_INFO_REQUEST + SEPARATOR_CHAR + encryptedRequest;
        if (isDebug()) {
            plugin.getLogger().info("UDP [" + serverName + "] 发送服务器信息请求...");
        }
        String response = sendAndReceiveSync(request);

        if (response == null) {
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息请求无响应（超时或通信异常）。");
            }
            return null;
        }

        if (isDebug()) {
            plugin.getLogger().info("UDP [" + serverName + "] 收到响应: " + response.substring(0, Math.min(response.length(), 30)) + "...");
        }

        // 服务端返回 KEY_ERR，表示没有密钥
        if (response.startsWith(TYPE_KEY_ERROR)) {
            String payload = response.substring(TYPE_KEY_ERROR.length() + 1);
            int firstSep = payload.indexOf(SEPARATOR_CHAR);
            String errorCode = firstSep >= 0 ? payload.substring(firstSep + 1) : payload;

            if ("NO_KEY".equals(errorCode) && !isRetry) {
                plugin.getLogger().info("UDP [" + serverName + "] 服务端没有密钥，正在同步密钥...");
                if (exchangeKey()) {
                    plugin.getLogger().info("UDP [" + serverName + "] 密钥同步成功，重新请求服务器信息...");
                    return doRequestServerInfo(true);
                } else {
                    plugin.getLogger().warning("UDP [" + serverName + "] 密钥同步失败，无法获取服务器信息");
                    return null;
                }
            }

            plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息请求被拒绝: " + errorCode);
            return null;
        }

        // 解析响应: INFO_RESP|encryptedData
        if (!response.startsWith(TYPE_SERVER_INFO_RESPONSE)) {
            plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息响应格式错误");
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 预期 " + TYPE_SERVER_INFO_RESPONSE + "，实际收到: " + response.substring(0, Math.min(response.length(), 20)));
            }
            return null;
        }
        String encryptedPayload = response.substring(TYPE_SERVER_INFO_RESPONSE.length() + 1);

        // 解密响应
        String decrypted;
        try {
            decrypted = CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息响应解密失败: " + e.getMessage());
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 解密失败详情 - 密文长度: " + encryptedPayload.length() + ", 密钥长度: " + (secretKey != null ? secretKey.length() : "null"));
            }
            return null;
        }

        if (isDebug()) {
            plugin.getLogger().info("UDP [" + serverName + "] 解密后数据: " + decrypted);
        }

        // 解析数据: serverName|online|max|onlineStatus
        String[] dataParts = decrypted.split("\\|", 4);
        if (dataParts.length < 4) {
            plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息数据格式错误: " + decrypted);
            return null;
        }

        try {
            String serverName = dataParts[0];
            int online = Integer.parseInt(dataParts[1]);
            int maxPlayers = Integer.parseInt(dataParts[2]);
            boolean onlineStatus = Boolean.parseBoolean(dataParts[3]);

            BungeeMessenger.ServerStatus status = new BungeeMessenger.ServerStatus(serverName, online, maxPlayers, onlineStatus);
            statusCache.put(serverName, status);

            if (isDebug()) {
                plugin.getLogger().info("UDP [" + serverName + "] 获取到服务器信息: " + status);
            }

            return status;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("UDP [" + serverName + "] 服务器信息数据解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 异步请求服务器信息
     */
    public CompletableFuture<BungeeMessenger.ServerStatus> requestServerInfoAsync() {
        CompletableFuture<BungeeMessenger.ServerStatus> future = new CompletableFuture<>();
        if (executor == null || !initialized) {
            future.complete(null);
            return future;
        }
        executor.submit(() -> {
            BungeeMessenger.ServerStatus status = requestServerInfo();
            future.complete(status);
        });
        return future;
    }

    /**
     * 同步发送请求并等待响应
     */
    private String sendAndReceiveSync(String data) {
        if (socket == null || socket.isClosed()) {
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] Socket 未初始化或已关闭，无法发送请求。");
            }
            return null;
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] sendData = data.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);

            if (isDebug()) {
                plugin.getLogger().info("UDP [" + serverName + "] 发送数据到 " + host + ":" + port + "，长度: " + sendData.length + " 字节");
            }
            socket.send(sendPacket);

            byte[] buffer = new byte[4096];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);

            String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
            if (isDebug()) {
                plugin.getLogger().info("UDP [" + serverName + "] 收到来自 " + receivePacket.getAddress().getHostAddress() + ":" + receivePacket.getPort() + " 的响应，长度: " + receivePacket.getLength() + " 字节");
            }
            return response;
        } catch (SocketTimeoutException e) {
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 请求超时（" + timeout + "ms）: " + e.getMessage());
            }
            return null;
        } catch (IOException e) {
            if (isDebug()) {
                plugin.getLogger().warning("UDP [" + serverName + "] 通信异常: " + e.getMessage());
            }
            return null;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getServerName() {
        return serverName;
    }

    public ConcurrentHashMap<String, BungeeMessenger.ServerStatus> getStatusCache() {
        return new ConcurrentHashMap<>(statusCache);
    }
}
