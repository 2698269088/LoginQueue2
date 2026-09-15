package top.mcocet.loginqueue2.match;

import org.bukkit.entity.Player;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.udp.UDPClient;
import top.mcocet.loginqueue2.util.CryptoUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小游戏对局管理器（MINIGAME 模式）
 * 接收小游戏子服（LoginQueue2Online）上报的对局状态，并负责对局排队放行决策
 *
 * 放行模式（minigame.release-mode）:
 *   GATHER_FIRST - 凑齐优先：优先为能一次性凑满开局人数的对局放行，凑不齐整局时允许按空位补位
 *   GATHER_ONLY  - 凑齐模式：只有队列人数足够凑满对局开局人数时才放行
 *   FILL_ONLY    - 补位模式：只要对局有空位就按队列顺序放行
 */
public class MinigameMatchManager {

    private static final String SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = ",";

    public static final String MODE_GATHER_FIRST = "GATHER_FIRST";
    public static final String MODE_GATHER_ONLY = "GATHER_ONLY";
    public static final String MODE_FILL_ONLY = "FILL_ONLY";

    public static final String JOIN_MODE_BATCH = "BATCH";
    public static final String JOIN_MODE_SEQUENTIAL = "SEQUENTIAL";

    private final LoginQueue2 plugin;
    // key = serverName|matchId
    private final Map<String, MatchInfo> matches = new ConcurrentHashMap<>();

    public MinigameMatchManager(LoginQueue2 plugin) {
        this.plugin = plugin;
    }

    // ==================== 配置读取 ====================

    /**
     * 默认小游戏服务器名称（玩家放行后的跳转目标）
     */
    public String getDefaultTargetServer() {
        return plugin.getConfig().getString("minigame.target-server", "minigames");
    }

    /**
     * 对局放行模式
     */
    public String getReleaseMode() {
        String mode = plugin.getConfig().getString("minigame.release-mode", MODE_GATHER_FIRST);
        if (mode == null) {
            return MODE_GATHER_FIRST;
        }
        mode = mode.toUpperCase();
        if (!MODE_GATHER_FIRST.equals(mode) && !MODE_GATHER_ONLY.equals(mode) && !MODE_FILL_ONLY.equals(mode)) {
            return MODE_GATHER_FIRST;
        }
        return mode;
    }

    /**
     * 是否启用凑齐优先逻辑（GATHER_FIRST / GATHER_ONLY）
     */
    public boolean isGatherEnabled() {
        return !MODE_FILL_ONLY.equals(getReleaseMode());
    }

    /**
     * 是否允许按空位补位（GATHER_FIRST / FILL_ONLY）
     */
    public boolean isFillEnabled() {
        return !MODE_GATHER_ONLY.equals(getReleaseMode());
    }

    /**
     * 是否为纯凑齐模式（GATHER_ONLY）
     */
    public boolean isGatherOnly() {
        return MODE_GATHER_ONLY.equals(getReleaseMode());
    }

    /**
     * 玩家加入对局的方式
     *   BATCH      - 批量加入：凑齐或补位时连续放行多名玩家，同时进入服务器
     *   SEQUENTIAL - 逐个加入：每次只放行一名玩家，两次放行间隔 join-interval 秒，平滑进入服务器
     */
    public String getJoinMode() {
        String mode = plugin.getConfig().getString("minigame.join-mode", JOIN_MODE_BATCH);
        if (mode == null) {
            return JOIN_MODE_BATCH;
        }
        mode = mode.toUpperCase();
        if (!JOIN_MODE_BATCH.equals(mode) && !JOIN_MODE_SEQUENTIAL.equals(mode)) {
            return JOIN_MODE_BATCH;
        }
        return mode;
    }

    /**
     * 是否为逐个加入模式（SEQUENTIAL）
     */
    public boolean isSequentialJoin() {
        return JOIN_MODE_SEQUENTIAL.equals(getJoinMode());
    }

    /**
     * 逐个加入模式下，两次放行之间的最小间隔（秒）
     */
    public int getJoinInterval() {
        return Math.max(1, plugin.getConfig().getInt("minigame.join-interval", 2));
    }

    /**
     * 对局凑齐等待超时（秒），超时后不再等待凑齐并通知子服开始游戏，0 表示不启用
     */
    public int getGatherTimeout() {
        return plugin.getConfig().getInt("minigame.gather-timeout", 120);
    }

    /**
     * 对局上报超时（秒），超过此时间未收到某服务器上报则清除其对局
     */
    public int getReportTimeout() {
        return plugin.getConfig().getInt("minigame.report-timeout", 15);
    }

    /**
     * 已放行玩家确认宽限期（毫秒）
     * 放行后在该时间窗口内，放行记录会计入对局人数，防止对局上报延迟导致超发
     */
    public long getPendingGrace() {
        return plugin.getConfig().getLong("minigame.pending-grace", 10000);
    }

    // ==================== 对局数据处理 ====================

    /**
     * 处理子服上报的对局列表
     *
     * @param serverName       上报来源服务器名称
     * @param decryptedPayload 解密后的上报数据，格式: count|id,state,players,min,max,createdAt|...
     */
    public void handleMatchReport(String serverName, String decryptedPayload) {
        if (decryptedPayload == null || decryptedPayload.isEmpty()) {
            return;
        }
        String[] parts = decryptedPayload.split("\\|");
        int count;
        try {
            count = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> reportedKeys = new HashSet<>();
        for (int i = 1; i <= count && i < parts.length; i++) {
            String[] fields = parts[i].split(FIELD_SEPARATOR, 6);
            if (fields.length < 5) {
                continue;
            }
            String matchId = fields[0].trim();
            if (matchId.isEmpty()) {
                continue;
            }
            String state = fields[1].trim().toUpperCase();
            int players;
            int minPlayers;
            int maxPlayers;
            long createdAt;
            try {
                players = Integer.parseInt(fields[2].trim());
                minPlayers = Integer.parseInt(fields[3].trim());
                maxPlayers = Integer.parseInt(fields[4].trim());
                createdAt = fields.length >= 6 ? Long.parseLong(fields[5].trim()) : now;
            } catch (NumberFormatException e) {
                continue;
            }
            if (minPlayers <= 0) {
                minPlayers = 1;
            }
            if (maxPlayers < minPlayers) {
                maxPlayers = minPlayers;
            }

            String key = buildKey(serverName, matchId);
            reportedKeys.add(key);
            MatchInfo info = matches.get(key);
            if (info == null) {
                info = new MatchInfo(key, serverName, matchId, now);
                matches.put(key, info);
            }
            info.state = state;
            info.players = Math.max(0, players);
            info.minPlayers = minPlayers;
            info.maxPlayers = maxPlayers;
            info.createdAt = createdAt;
            info.lastReportTime = now;
        }

        // 移除该服务器本次未上报的对局（已被子服删除）
        for (Iterator<Map.Entry<String, MatchInfo>> it = matches.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, MatchInfo> entry = it.next();
            MatchInfo info = entry.getValue();
            if (info.serverName.equals(serverName) && !reportedKeys.contains(entry.getKey())) {
                it.remove();
            }
        }

        if (plugin.isDebug()) {
            plugin.getLogger().info(plugin.getLanguageManager().getLogMessage("minigame-match-report",
                    "server", serverName, "count", String.valueOf(reportedKeys.size())));
        }
    }

    /**
     * 周期清理：移除上报超时的对局与过期的放行记录
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long reportTimeoutMillis = Math.max(1, getReportTimeout()) * 1000L;
        matches.values().removeIf(info -> now - info.lastReportTime > reportTimeoutMillis);

        long grace = getPendingGrace();
        for (MatchInfo info : matches.values()) {
            info.pending.removeIf(p -> now - p.releaseTime > grace);
        }
    }

    /**
     * 周期检查：对局凑齐超时处理
     */
    public void checkTimeouts() {
        int gatherTimeout = getGatherTimeout();
        if (gatherTimeout <= 0) {
            return;
        }
        long timeoutMillis = gatherTimeout * 1000L;
        long now = System.currentTimeMillis();
        for (MatchInfo info : matches.values()) {
            if (!info.isWaiting()) {
                continue;
            }
            // 超时仍未凑齐开局人数：不再等待凑齐
            if (!info.timeoutFired && !info.isGathered()
                    && now - info.firstSeenTime >= timeoutMillis) {
                info.timeoutFired = true;
                if (plugin.isDebug()) {
                    plugin.getLogger().info(plugin.getLanguageManager().getLogMessage("minigame-match-timeout",
                            "server", info.serverName, "match", info.matchId, "seconds", String.valueOf(gatherTimeout)));
                }
            }
            // 已超时且对局内已有玩家：通知子服开始游戏
            if (info.timeoutFired && !info.startNotified && effectivePlayers(info) > 0) {
                info.startNotified = true;
                sendMatchStart(info.serverName, info.matchId, "TIMEOUT");
            }
        }
    }

    /**
     * 计算对局有效人数（子服上报人数 + 未确认的放行记录）
     */
    public int effectivePlayers(MatchInfo info) {
        return info.players + info.pending.size();
    }

    /**
     * 获取所有可加入的对局（等待中且未满员），按创建时间排序（先创建的先凑人）
     */
    public List<MatchInfo> getJoinableMatches() {
        List<MatchInfo> list = new ArrayList<>();
        for (MatchInfo info : matches.values()) {
            if (info.isWaiting() && effectivePlayers(info) < info.maxPlayers) {
                list.add(info);
            }
        }
        list.sort(Comparator.comparingLong((MatchInfo m) -> m.createdAt)
                .thenComparing(m -> m.matchId));
        return list;
    }

    /**
     * 按服务器名称与对局 ID 查找对局（供子服放行请求使用）
     */
    public MatchInfo getMatch(String serverName, String matchId) {
        if (serverName == null || matchId == null) {
            return null;
        }
        return matches.get(buildKey(serverName, matchId));
    }

    /**
     * 获取对局快照（按服务器与创建时间排序），用于命令展示
     */
    public List<MatchInfo> snapshotMatches() {
        List<MatchInfo> list = new ArrayList<>(matches.values());
        list.sort(Comparator.comparing((MatchInfo m) -> m.serverName)
                .thenComparingLong(m -> m.createdAt)
                .thenComparing(m -> m.matchId));
        return list;
    }

    public int getTotalMatchCount() {
        return matches.size();
    }

    public int getWaitingMatchCount() {
        int count = 0;
        for (MatchInfo info : matches.values()) {
            if (info.isWaiting()) {
                count++;
            }
        }
        return count;
    }

    public int getRunningMatchCount() {
        int count = 0;
        for (MatchInfo info : matches.values()) {
            if ("RUNNING".equals(info.state)) {
                count++;
            }
        }
        return count;
    }

    public int getJoinableCount() {
        return getJoinableMatches().size();
    }

    // ==================== 放行记录与通知 ====================

    /**
     * 记录一次放行（在收到子服下一次上报前计入对局人数）
     */
    public void addPending(MatchInfo info, UUID uuid) {
        info.pending.add(new PendingRelease(uuid, System.currentTimeMillis()));
    }

    /**
     * 移除玩家的全部放行记录（玩家退出登录服时调用）
     */
    public void removePending(UUID uuid) {
        for (MatchInfo info : matches.values()) {
            info.pending.removeIf(p -> p.uuid.equals(uuid));
        }
    }

    /**
     * 通知子服某玩家已被放行进入指定对局
     */
    public void sendMatchJoin(String serverName, String matchId, UUID uuid) {
        sendToServer(serverName, "MATCH_JOIN", uuid.toString() + SEPARATOR + matchId);
    }

    /**
     * 通知子服某对局可以开始游戏（凑齐超时降级）
     */
    public void sendMatchStart(String serverName, String matchId, String reason) {
        sendToServer(serverName, "MATCH_START", matchId + SEPARATOR + reason);
    }

    /**
     * 通知子服结束指定对局（状态切换为已结束）
     */
    public void sendMatchEnd(String serverName, String matchId, String reason) {
        sendToServer(serverName, "MATCH_END", matchId + SEPARATOR + reason);
    }

    private void sendToServer(String serverName, String type, String rawPayload) {
        UDPClient client = findClient(serverName);
        if (client == null) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("minigame-send-no-client", "server", serverName));
            return;
        }
        String secretKey = client.getSecretKey();
        if (secretKey == null || secretKey.isEmpty()) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("minigame-send-no-key", "server", serverName));
            return;
        }
        String encrypted;
        try {
            encrypted = CryptoUtil.encryptWithStringKey(rawPayload, secretKey);
        } catch (Exception e) {
            plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("minigame-send-encrypt-failed",
                    "server", serverName, "error", e.getMessage()));
            return;
        }
        client.sendRawData(type + SEPARATOR + serverName + SEPARATOR + encrypted);
    }

    private UDPClient findClient(String serverName) {
        for (UDPClient client : plugin.getMessenger().getUdpClients()) {
            if (client.getServerName().equals(serverName)) {
                return client;
            }
        }
        return null;
    }

    // ==================== 内部类 ====================

    private String buildKey(String serverName, String matchId) {
        return serverName + SEPARATOR + matchId;
    }

    /**
     * 放行记录
     */
    private static class PendingRelease {
        final UUID uuid;
        final long releaseTime;

        PendingRelease(UUID uuid, long releaseTime) {
            this.uuid = uuid;
            this.releaseTime = releaseTime;
        }
    }

    /**
     * 对局信息
     */
    public static class MatchInfo {
        private final String key;
        private final String serverName;
        private final String matchId;
        private volatile String state = "WAITING";
        private volatile int players;
        private volatile int minPlayers = 1;
        private volatile int maxPlayers = 1;
        private volatile long createdAt;
        private volatile long firstSeenTime;
        private volatile long lastReportTime;
        // 是否已触发凑齐超时（触发后不再等待凑齐，允许补位放行）
        private volatile boolean timeoutFired = false;
        // 是否已发送开始游戏通知（避免重复发送）
        private volatile boolean startNotified = false;
        private final List<PendingRelease> pending = new ArrayList<>();

        MatchInfo(String key, String serverName, String matchId, long firstSeenTime) {
            this.key = key;
            this.serverName = serverName;
            this.matchId = matchId;
            this.firstSeenTime = firstSeenTime;
            this.createdAt = firstSeenTime;
            this.lastReportTime = firstSeenTime;
        }

        public String getServerName() {
            return serverName;
        }

        public String getMatchId() {
            return matchId;
        }

        public String getState() {
            return state;
        }

        public int getMinPlayers() {
            return minPlayers;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public int getPlayers() {
            return players;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public boolean isWaiting() {
            return "WAITING".equals(state);
        }

        public boolean isGathered() {
            return players + pending.size() >= minPlayers;
        }

        public boolean isTimeoutFired() {
            return timeoutFired;
        }

        /**
         * 对局已存在时长（秒）
         */
        public long getAgeSeconds() {
            return (System.currentTimeMillis() - firstSeenTime) / 1000L;
        }
    }
}
