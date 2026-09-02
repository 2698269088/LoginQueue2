package top.mcocet.loginqueue2limbo.udp;

import com.loohp.limbo.Limbo;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.util.CryptoUtil;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

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

    /** 协议版本号：用于跨插件通信版本兼容性检查 */
    public static final String PROTOCOL_VERSION = "1.6";

    private static final String TYPE_KEY_REQUEST = "KEY_REQ";
    private static final String TYPE_KEY_EXCHANGE = "KEY_EXCH";
    private static final String TYPE_SERVER_INFO_REQUEST = "INFO_REQ";
    private static final String TYPE_KEY_RESPONSE = "KEY_RESP";
    private static final String TYPE_KEY_ERROR = "KEY_ERR";
    private static final String TYPE_SERVER_INFO_RESPONSE = "INFO_RESP";
    private static final char SEPARATOR_CHAR = '|';

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private final String serverName;
    private final String host;
    private final int port;
    private final int gamePort;
    private final int timeout;
    private final String configuredKey;
    private final String plannedKey;
    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile boolean initialized = false;
    private String secretKey;
    private final ConcurrentHashMap<String, BungeeMessenger.ServerStatus> statusCache = new ConcurrentHashMap<>();

    public UDPClient(LoginQueue2Limbo plugin, String serverName, String host, int port, int timeout, String configuredKey, String plannedKey) {
        this(plugin, serverName, host, port, -1, timeout, configuredKey, plannedKey);
    }

    public UDPClient(LoginQueue2Limbo plugin, String serverName, String host, int port, int gamePort, int timeout, String configuredKey, String plannedKey) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.serverName = serverName;
        this.host = host;
        this.port = port;
        this.gamePort = gamePort;
        this.timeout = timeout > 0 ? timeout : 3000;
        this.configuredKey = configuredKey;
        this.plannedKey = plannedKey;
    }

    /**
     * 初始化 UDP 客户端
     *
     * @return 是否初始化成功
     */
    private boolean isDebug() {
        return plugin.isDebug();
    }

    private void log(String message) {
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + message);
    }

    public boolean init() {
        if (initialized) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-client-already-initialized", "server", serverName));
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

            log(languageManager.getLogMessage("udp-client-initialized", "server", serverName, "host", host, "port", String.valueOf(port)));
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-timeout-set", "server", serverName, "timeout", String.valueOf(timeout)));
            }

            // 如果配置了密钥，直接使用；否则自动生成密钥并同步给服务端
            if (configuredKey != null && !configuredKey.isEmpty()) {
                secretKey = configuredKey;
                log(languageManager.getLogMessage("udp-using-configured-key", "server", serverName));
                if (isDebug()) {
                    log(languageManager.getLogMessage("udp-key-length", "server", serverName, "length", String.valueOf(configuredKey.length())));
                }
            } else {
                // 自动生成密钥，并尝试同步给服务端
                secretKey = CryptoUtil.generateRandomKey();
                log(languageManager.getLogMessage("udp-auto-generating-key", "server", serverName));
                if (isDebug()) {
                    log(languageManager.getLogMessage("udp-auto-key-length", "server", serverName, "length", String.valueOf(secretKey.length())));
                    if (plannedKey != null && !plannedKey.isEmpty()) {
                        log(languageManager.getLogMessage("udp-using-planned-key", "server", serverName));
                    } else {
                        log(languageManager.getLogMessage("udp-no-planned-key", "server", serverName));
                    }
                }
                if (!exchangeKey()) {
                    log(languageManager.getLogMessage("udp-key-sync-failed", "server", serverName));
                    closeSocket();
                    return false;
                }
                log(languageManager.getLogMessage("udp-key-synced", "server", serverName));
            }

            initialized = true;
            log(languageManager.getLogMessage("udp-init-complete", "server", serverName));
            return true;
        } catch (SocketException e) {
            log(languageManager.getLogMessage("udp-init-failed", "server", serverName, "error", e.getMessage()));
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
        log(languageManager.getLogMessage("udp-client-shutdown", "server", serverName));
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
                log(languageManager.getLogMessage("udp-using-planned-key", "server", serverName));
            }
        } else {
            // 明文传输（向后兼容）
            keyToSend = secretKey;
        }

        String request = TYPE_KEY_EXCHANGE + SEPARATOR_CHAR + keyToSend;
        if (isDebug()) {
            log(languageManager.getLogMessage("udp-sending-key-exchange", "server", serverName));
        }
        String response = sendAndReceiveSync(request);

        if (response == null) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-key-exchange-no-response", "server", serverName));
            }
            return false;
        }

        if (isDebug()) {
            log(languageManager.getLogMessage("udp-key-exchange-response", "server", serverName, "response", response.substring(0, Math.min(response.length(), 50))));
        }

        // 服务端返回 KEY_ERR 表示没有密钥且不接受同步（旧版本兼容）
        if (response.startsWith(TYPE_KEY_ERROR)) {
            log(languageManager.getLogMessage("udp-key-exchange-rejected", "server", serverName, "reason", response.substring(TYPE_KEY_ERROR.length() + 1)));
            return false;
        }

        // 解析响应: KEY_RESP|serverName|OK
        if (!response.startsWith(TYPE_KEY_RESPONSE)) {
            log(languageManager.getLogMessage("udp-key-exchange-format-error", "server", serverName));
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-key-exchange-unexpected", "server", serverName, "expected", TYPE_KEY_RESPONSE, "actual", response.substring(0, Math.min(response.length(), 20))));
            }
            return false;
        }
        String payload = response.substring(TYPE_KEY_RESPONSE.length() + 1);
        int firstSep = payload.indexOf(SEPARATOR_CHAR);
        if (firstSep < 0) {
            log(languageManager.getLogMessage("udp-key-exchange-missing-separator", "server", serverName));
            return false;
        }
        String serverName = payload.substring(0, firstSep);
        String result = payload.substring(firstSep + 1);
        if (!"OK".equals(result)) {
            log(languageManager.getLogMessage("udp-key-exchange-server-returned", "server", serverName, "result", result));
            return false;
        }
        log(languageManager.getLogMessage("udp-key-synced-to-server", "server", serverName));
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
            log(languageManager.getLogMessage("udp-encrypt-failed", "error", e.getMessage()));
            if (isDebug()) {
                e.printStackTrace();
            }
            return null;
        }

        String request = TYPE_SERVER_INFO_REQUEST + SEPARATOR_CHAR + encryptedRequest;
        if (isDebug()) {
            log(languageManager.getLogMessage("udp-sending-server-info-request", "server", serverName));
        }
        String response = sendAndReceiveSync(request);

        if (response == null) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-server-info-no-response", "server", serverName));
            }
            return null;
        }

        if (isDebug()) {
            log(languageManager.getLogMessage("udp-received-response", "server", serverName, "response", response.substring(0, Math.min(response.length(), 30))));
        }

        // 服务端返回 KEY_ERR，表示没有密钥
        if (response.startsWith(TYPE_KEY_ERROR)) {
            String payload = response.substring(TYPE_KEY_ERROR.length() + 1);
            int firstSep = payload.indexOf(SEPARATOR_CHAR);
            String errorCode = firstSep >= 0 ? payload.substring(firstSep + 1) : payload;

            if ("NO_KEY".equals(errorCode) && !isRetry) {
                log(languageManager.getLogMessage("udp-server-no-key", "server", serverName));
                if (exchangeKey()) {
                    log(languageManager.getLogMessage("udp-key-resync-success", "server", serverName));
                    return doRequestServerInfo(true);
                } else {
                    log(languageManager.getLogMessage("udp-key-resync-failed", "server", serverName));
                    return null;
                }
            }

            log(languageManager.getLogMessage("udp-server-info-rejected", "server", serverName, "errorCode", errorCode));
            return null;
        }

        // 解析响应: INFO_RESP|encryptedData
        if (!response.startsWith(TYPE_SERVER_INFO_RESPONSE)) {
            log(languageManager.getLogMessage("udp-server-info-format-error", "server", serverName));
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-server-info-unexpected", "server", serverName, "expected", TYPE_SERVER_INFO_RESPONSE, "actual", response.substring(0, Math.min(response.length(), 20))));
            }
            return null;
        }
        String encryptedPayload = response.substring(TYPE_SERVER_INFO_RESPONSE.length() + 1);

        // 解密响应
        String decrypted;
        try {
            decrypted = CryptoUtil.decryptWithStringKey(encryptedPayload, secretKey);
        } catch (Exception e) {
            log(languageManager.getLogMessage("udp-decrypt-failed", "server", serverName, "error", e.getMessage()));
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-decrypt-details", "server", serverName, "cipherLength", String.valueOf(encryptedPayload.length()), "keyLength", secretKey != null ? String.valueOf(secretKey.length()) : "null"));
            }
            return null;
        }

        if (isDebug()) {
            log(languageManager.getLogMessage("udp-decrypted-data", "server", serverName, "data", decrypted));
        }

        // 解析数据: serverName|online|max|onlineStatus|tps|usedMemory|maxMemory
        String[] dataParts = decrypted.split("\\|", 7);
        if (dataParts.length < 4) {
            log(languageManager.getLogMessage("udp-server-info-data-error", "server", serverName, "data", decrypted));
            return null;
        }

        try {
            String serverName = dataParts[0];
            int online = Integer.parseInt(dataParts[1]);
            int maxPlayers = Integer.parseInt(dataParts[2]);
            boolean onlineStatus = Boolean.parseBoolean(dataParts[3]);
            double tps = dataParts.length >= 5 ? Double.parseDouble(dataParts[4]) : 20.0;
            long usedMemory = dataParts.length >= 6 ? Long.parseLong(dataParts[5]) : 0;
            long maxMemory = dataParts.length >= 7 ? Long.parseLong(dataParts[6]) : 0;
            String remoteProtocolVersion = dataParts.length >= 8 ? dataParts[7] : null;

            BungeeMessenger.ServerStatus status = new BungeeMessenger.ServerStatus(serverName, online, maxPlayers, onlineStatus, tps, usedMemory, maxMemory);
            statusCache.put(serverName, status);

            // 协议版本兼容性检查
            if (remoteProtocolVersion != null && !remoteProtocolVersion.isEmpty()
                    && !PROTOCOL_VERSION.equals(remoteProtocolVersion)) {
                log(languageManager.getLogMessage("protocol-version-mismatch-header"));
                log(languageManager.getLogMessage("protocol-version-mismatch", "localVersion", PROTOCOL_VERSION, "server", serverName, "remoteVersion", remoteProtocolVersion));
                log(languageManager.getLogMessage("protocol-version-mismatch-suggestion"));
                log(languageManager.getLogMessage("protocol-version-mismatch-note"));
                log(languageManager.getLogMessage("protocol-version-mismatch-header"));
            }

            if (isDebug()) {
                log(languageManager.getLogMessage("udp-get-server-info", "server", serverName, "status", status.toString()));
            }

            return status;
        } catch (NumberFormatException e) {
            log(languageManager.getLogMessage("udp-server-info-parse-failed", "server", serverName, "error", e.getMessage()));
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
                log(languageManager.getLogMessage("udp-socket-not-ready", "server", serverName));
            }
            return null;
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] sendData = data.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);

            if (isDebug()) {
                log(languageManager.getLogMessage("udp-sending-data", "server", serverName, "host", host, "port", String.valueOf(port), "length", String.valueOf(sendData.length)));
            }
            socket.send(sendPacket);

            byte[] buffer = new byte[4096];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);

            String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-received-from", "server", serverName, "host", receivePacket.getAddress().getHostAddress(), "port", String.valueOf(receivePacket.getPort()), "length", String.valueOf(receivePacket.getLength())));
            }
            return response;
        } catch (SocketTimeoutException e) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-request-timeout", "server", serverName, "timeout", String.valueOf(timeout), "error", e.getMessage()));
            }
            return null;
        } catch (IOException e) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-communication-error", "server", serverName, "error", e.getMessage()));
            }
            return null;
        }
    }

    /**
     * 使用 Minecraft Server List Ping 协议直接获取服务器状态
     * 无需 UDP 通信，直接 TCP 连接游戏端口查询
     *
     * @param targetIp   目标IP，若为null则使用host
     * @param targetPort 目标端口
     * @return 服务器状态，失败返回 null
     */
    public BungeeMessenger.ServerStatus requestServerInfoViaMSLP(String targetIp, int targetPort) {
        String ip = targetIp != null ? targetIp : host;
        if (isDebug()) {
            log(languageManager.getLogMessage("mslp-check-start", "server", serverName, "ip", ip, "port", String.valueOf(targetPort)));
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, targetPort), timeout);

            java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
            java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());

            // 发送握手包
            java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream handshakeData = new java.io.DataOutputStream(handshake);
            writeVarInt(handshakeData, 0x00); // Packet ID
            writeVarInt(handshakeData, -1); // Protocol version (ping)
            writeString(handshakeData, ip);
            handshakeData.writeShort(targetPort);
            writeVarInt(handshakeData, 1); // Next state: status

            byte[] handshakeBytes = handshake.toByteArray();
            writeVarInt(out, handshakeBytes.length);
            out.write(handshakeBytes);

            // 发送状态请求包
            out.writeByte(0x01); // Length: 1
            out.writeByte(0x00); // Packet ID: 0x00

            // 读取响应长度
            int length = readVarInt(in);
            // 读取包ID
            int packetId = readVarInt(in);
            if (packetId != 0x00) {
                return null;
            }
            // 读取JSON字符串长度
            int jsonLength = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);

            if (isDebug()) {
                log(languageManager.getLogMessage("mslp-response", "server", serverName, "json", json.substring(0, Math.min(json.length(), 200))));
            }

            // 解析JSON
            com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
            com.google.gson.JsonObject players = root.getAsJsonObject("players");
            int online = players != null && players.has("online") ? players.get("online").getAsInt() : 0;
            int maxPlayers = players != null && players.has("max") ? players.get("max").getAsInt() : 0;

            BungeeMessenger.ServerStatus status = new BungeeMessenger.ServerStatus(serverName, online, maxPlayers, true);
            statusCache.put(serverName, status);

            if (isDebug()) {
                log(languageManager.getLogMessage("mslp-success", "server", serverName, "status", status.toString()));
            }
            return status;
        } catch (Exception e) {
            if (isDebug()) {
                log(languageManager.getLogMessage("mslp-failed", "server", serverName, "error", e.getMessage()));
            }
            return null;
        }
    }

    private void writeVarInt(java.io.DataOutputStream out, int value) throws java.io.IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private void writeString(java.io.DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private int readVarInt(java.io.DataInputStream in) throws java.io.IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        while (true) {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new java.io.IOException("VarInt too big");
        }
        return value;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getServerName() {
        return serverName;
    }

    public int getGamePort() {
        return gamePort;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public int getTimeout() {
        return timeout;
    }

    /**
     * 发送原始数据到该 UDP 客户端配置的服务器地址
     *
     * @param data 要发送的数据
     * @return 是否发送成功
     */
    public boolean sendRawData(String data) {
        if (socket == null || socket.isClosed()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, port);
            socket.send(packet);
            return true;
        } catch (IOException e) {
            if (isDebug()) {
                log(languageManager.getLogMessage("udp-send-raw-failed", "server", serverName, "error", e.getMessage()));
            }
            return false;
        }
    }

    public ConcurrentHashMap<String, BungeeMessenger.ServerStatus> getStatusCache() {
        return new ConcurrentHashMap<>(statusCache);
    }
}
