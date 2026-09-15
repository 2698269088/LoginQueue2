package top.mcocet.loginqueue2online.match;

import org.bukkit.entity.Player;
import top.mcocet.loginqueue2online.LoginQueue2Online;
import top.mcocet.loginqueue2online.match.event.MatchAssignEvent;
import top.mcocet.loginqueue2online.match.event.MatchEndEvent;
import top.mcocet.loginqueue2online.match.event.MatchStartEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对局管理器
 * 负责对局创建、状态维护、玩家分配确认与上报载荷构建
 * 所有涉及 Bukkit API 与事件调用的方法都必须在主线程执行
 */
public class MatchManager {

    private final LoginQueue2Online plugin;
    private final Map<String, Match> matches = new ConcurrentHashMap<>();
    // 玩家 UUID -> 对局 ID
    private final Map<UUID, String> assignedIndex = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public MatchManager(LoginQueue2Online plugin) {
        this.plugin = plugin;
    }

    /**
     * 是否启用对局功能
     */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("match.enabled", false);
    }

    /**
     * 创建对局（自动生成 ID）
     */
    public Match createMatch(int minPlayers, int maxPlayers) {
        String id;
        do {
            id = "m" + idCounter.incrementAndGet();
        } while (matches.containsKey(id));
        return createMatch(id, minPlayers, maxPlayers);
    }

    /**
     * 创建对局（指定 ID）
     * ID 只允许字母、数字、下划线与连字符
     *
     * @return 创建的对局，ID 非法或已存在时返回 null
     */
    public Match createMatch(String id, int minPlayers, int maxPlayers) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        if (matches.containsKey(id)) {
            return null;
        }
        Match match = new Match(id, minPlayers, maxPlayers);
        matches.put(id, match);
        return match;
    }

    /**
     * 删除对局并清理玩家分配记录
     */
    public boolean removeMatch(String id) {
        Match match = matches.remove(id);
        if (match == null) {
            return false;
        }
        assignedIndex.values().removeIf(matchId -> matchId.equals(id));
        return true;
    }

    public Match getMatch(String id) {
        return id == null ? null : matches.get(id);
    }

    /**
     * 获取全部对局副本
     */
    public Collection<Match> getMatches() {
        return new ArrayList<>(matches.values());
    }

    public int getMatchCount() {
        return matches.size();
    }

    /**
     * 设置对局状态
     */
    public boolean setState(String id, MatchState state) {
        Match match = matches.get(id);
        if (match == null) {
            return false;
        }
        match.setState(state);
        return true;
    }

    /**
     * 设置手动人数（null 取消手动模式，恢复自动统计）
     */
    public boolean setManualPlayers(String id, Integer players) {
        Match match = matches.get(id);
        if (match == null) {
            return false;
        }
        if (players != null && players < 0) {
            players = 0;
        }
        match.setManualPlayers(players);
        return true;
    }

    /**
     * 强制开始对局（仅允许从等待中切换为进行中）
     */
    public boolean forceStart(String id) {
        Match match = matches.get(id);
        if (match == null || !match.isWaiting()) {
            return false;
        }
        startMatch(match, "MANUAL");
        return true;
    }

    /**
     * 处理主插件放行通知（MATCH_JOIN）
     * 必须在主线程调用
     *
     * @return 对局是否存在
     */
    public boolean handleMatchJoin(UUID uuid, String matchId) {
        Match match = matches.get(matchId);
        if (match == null) {
            return false;
        }
        if (match.addAssigned(uuid)) {
            assignedIndex.put(uuid, matchId);
        }
        MatchAssignEvent event = new MatchAssignEvent(uuid, matchId);
        plugin.getServer().getPluginManager().callEvent(event);
        return true;
    }

    /**
     * 处理主插件开始游戏通知（MATCH_START，凑齐超时降级）
     * 必须在主线程调用
     *
     * @param reason 开始原因，如 TIMEOUT
     * @return 是否成功切换为进行中
     */
    public boolean handleMatchStart(String matchId, String reason) {
        Match match = matches.get(matchId);
        if (match == null || !match.isWaiting()) {
            return false;
        }
        startMatch(match, reason);
        return true;
    }

    private void startMatch(Match match, String reason) {
        match.markStarted(reason);
        MatchStartEvent event = new MatchStartEvent(match.getId(), reason);
        plugin.getServer().getPluginManager().callEvent(event);
        executeCommands("match.commands.on-start", match, reason);
        plugin.getLogger().info("对局 " + match.getId() + " 已开始（原因: " + reason + "）");
    }

    /**
     * 结束对局（状态切换为已结束，触发 MatchEndEvent 并执行 on-end 控制台命令）
     * 必须在主线程调用
     *
     * @return 是否成功结束（对局不存在或已结束时返回 false）
     */
    public boolean endMatch(String id) {
        return endMatch(id, "MANUAL");
    }

    /**
     * 结束对局（指定结束原因）
     * 必须在主线程调用
     *
     * @return 是否成功结束（对局不存在或已结束时返回 false）
     */
    public boolean endMatch(String id, String reason) {
        Match match = matches.get(id);
        if (match == null || match.isEnded()) {
            return false;
        }
        match.markEnded(reason);
        MatchEndEvent event = new MatchEndEvent(match.getId(), reason, new ArrayList<>(match.getAssignedPlayers().keySet()));
        plugin.getServer().getPluginManager().callEvent(event);
        executeCommands("match.commands.on-end", match, reason);
        plugin.getLogger().info("对局 " + match.getId() + " 已结束（原因: " + reason + "）");
        return true;
    }

    /**
     * 以控制台权限执行配置的命令列表
     * 命令无需以 "/" 开头；支持占位符 {match} {players} {min} {max} {reason}
     */
    private void executeCommands(String path, Match match, String reason) {
        List<String> commands = plugin.getConfig().getStringList(path);
        if (commands.isEmpty()) {
            return;
        }
        for (String raw : commands) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String cmd = raw.trim()
                    .replace("{match}", match.getId())
                    .replace("{players}", String.valueOf(match.getPlayers()))
                    .replace("{min}", String.valueOf(match.getMinPlayers()))
                    .replace("{max}", String.valueOf(match.getMaxPlayers()))
                    .replace("{reason}", reason == null ? "" : reason);
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (!cmd.isEmpty()) {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            }
        }
    }

    /**
     * 玩家加入服务器：标记其对局分配已连入
     * 必须在主线程调用
     */
    public void onPlayerJoin(Player player) {
        String matchId = assignedIndex.get(player.getUniqueId());
        if (matchId == null) {
            return;
        }
        Match match = matches.get(matchId);
        if (match != null) {
            match.markConnected(player.getUniqueId());
        }
    }

    /**
     * 玩家退出服务器：从对局分配中移除
     * 必须在主线程调用
     */
    public void onPlayerQuit(UUID uuid) {
        String matchId = assignedIndex.remove(uuid);
        if (matchId == null) {
            return;
        }
        Match match = matches.get(matchId);
        if (match != null) {
            match.removeAssigned(uuid);
        }
    }

    // ==================== API 查询与操作方法 ====================

    /**
     * 获取对局中全部已分配玩家（含等待连入与已连入）
     */
    public List<UUID> getAssignedUuids(String id) {
        Match match = matches.get(id);
        if (match == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(match.getAssignedPlayers().keySet());
    }

    /**
     * 获取对局中已分配但尚未连入服务器的玩家（等待中的玩家）
     */
    public List<UUID> getWaitingUuids(String id) {
        Match match = matches.get(id);
        if (match == null) {
            return new ArrayList<>();
        }
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Match.AssignedPlayer> entry : match.getAssignedPlayers().entrySet()) {
            if (!entry.getValue().isConnected()) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    /**
     * 获取对局中已连入服务器的玩家
     */
    public List<UUID> getConnectedUuids(String id) {
        Match match = matches.get(id);
        if (match == null) {
            return new ArrayList<>();
        }
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Match.AssignedPlayer> entry : match.getAssignedPlayers().entrySet()) {
            if (entry.getValue().isConnected()) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    /**
     * 获取玩家当前被分配到的对局 ID（未分配时返回 null）
     */
    public String getAssignedMatchId(UUID uuid) {
        return uuid == null ? null : assignedIndex.get(uuid);
    }

    /**
     * 主动将本服玩家分配到指定对局（供小游戏插件调用，不经过主插件排队）
     * 若玩家已分配至其他对局，会先将其从原对局移除
     * 必须在主线程调用（会触发 MatchAssignEvent）
     *
     * @return 对局是否存在
     */
    public boolean assignPlayerDirectly(UUID uuid, String matchId) {
        Match match = matches.get(matchId);
        if (match == null || uuid == null) {
            return false;
        }
        String oldMatchId = assignedIndex.remove(uuid);
        if (oldMatchId != null && !oldMatchId.equals(matchId)) {
            Match oldMatch = matches.get(oldMatchId);
            if (oldMatch != null) {
                oldMatch.removeAssigned(uuid);
            }
        }
        match.addAssigned(uuid);
        assignedIndex.put(uuid, matchId);

        MatchAssignEvent event = new MatchAssignEvent(uuid, matchId);
        plugin.getServer().getPluginManager().callEvent(event);
        return true;
    }

    /**
     * 将玩家从其对局中移除（供小游戏插件调用）
     *
     * @return 玩家是否原本已分配
     */
    public boolean unassignPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        String matchId = assignedIndex.remove(uuid);
        if (matchId == null) {
            return false;
        }
        Match match = matches.get(matchId);
        if (match != null) {
            match.removeAssigned(uuid);
        }
        return true;
    }

    /**
     * 周期清理：移除超时未连入的已分配玩家
     */
    public void cleanup() {
        int timeoutSeconds = plugin.getConfig().getInt("match.assign-timeout", 30);
        if (timeoutSeconds <= 0) {
            return;
        }
        long timeoutMillis = timeoutSeconds * 1000L;
        for (Match match : matches.values()) {
            List<UUID> removed = match.cleanupExpired(timeoutMillis);
            for (UUID uuid : removed) {
                assignedIndex.remove(uuid, match.getId());
            }
        }
    }

    /**
     * 构建对局上报载荷
     * 格式: count|id,state,players,min,max,createdAt|...
     */
    public String buildReportPayload() {
        List<Match> list = new ArrayList<>(matches.values());
        StringBuilder sb = new StringBuilder();
        sb.append(list.size());
        for (Match match : list) {
            sb.append('|')
                    .append(match.getId()).append(',')
                    .append(match.getState().name()).append(',')
                    .append(match.getPlayers()).append(',')
                    .append(match.getMinPlayers()).append(',')
                    .append(match.getMaxPlayers()).append(',')
                    .append(match.getCreatedAt());
        }
        return sb.toString();
    }
}
