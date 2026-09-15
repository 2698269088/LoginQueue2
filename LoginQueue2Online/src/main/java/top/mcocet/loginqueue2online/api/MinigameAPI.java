package top.mcocet.loginqueue2online.api;

import org.bukkit.Bukkit;
import top.mcocet.loginqueue2online.LoginQueue2Online;
import top.mcocet.loginqueue2online.match.Match;
import top.mcocet.loginqueue2online.match.MatchManager;
import top.mcocet.loginqueue2online.match.MatchState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LoginQueue2Online 小游戏对局公开 API
 *
 * 供部署在同服的小游戏插件调用，能力包括:
 *   - 对局管理: 创建/删除/开始/结束对局、设置状态与人数
 *   - 对局查询: 查询对局内等待连入/已连入的玩家、玩家当前所在对局
 *   - 主动分配: 将本服玩家直接计入对局（不经过登录服排队）
 *   - 排队查询: 查询登录服排队队列人数与玩家列表（缓存同步）
 *   - 请求放行: 请求登录服将排队中的指定玩家放行进入指定对局
 *
 * 使用方式:
 *   MinigameAPI api = MinigameAPI.get();
 *   if (api != null && api.isAvailable()) { ... }
 *
 * 线程约定:
 *   startMatch / endMatch / assignPlayer / unassignPlayer 会触发 Bukkit 事件并执行控制台命令，
 *   必须在主线程调用；
 *   其余查询与请求方法线程安全，可在任意线程调用。
 */
public final class MinigameAPI {

    private static volatile MinigameAPI instance;

    private final LoginQueue2Online plugin;

    private MinigameAPI(LoginQueue2Online plugin) {
        this.plugin = plugin;
    }

    /**
     * 由插件主类调用，其他插件请勿调用
     */
    public static void register(LoginQueue2Online plugin) {
        instance = new MinigameAPI(plugin);
    }

    /**
     * 由插件主类调用，其他插件请勿调用
     */
    public static void unregister() {
        instance = null;
    }

    /**
     * 获取 API 实例
     *
     * @return API 实例；插件未加载时返回 null
     */
    public static MinigameAPI get() {
        return instance;
    }

    /**
     * 对局功能是否可用（需 match.enabled: true 且 UDP 通信正常）
     */
    public boolean isAvailable() {
        MatchManager manager = plugin.getMatchManager();
        return manager != null && manager.isEnabled();
    }

    private MatchManager manager() {
        return plugin.getMatchManager();
    }

    // ==================== 对局管理 ====================

    /**
     * 创建对局（自动生成 ID）
     *
     * @return 创建的对局
     */
    public Match createMatch(int minPlayers, int maxPlayers) {
        return isAvailable() ? manager().createMatch(minPlayers, maxPlayers) : null;
    }

    /**
     * 创建对局（指定 ID，仅允许字母、数字、下划线与连字符）
     *
     * @return 创建的对局；ID 非法或已存在时返回 null
     */
    public Match createMatch(String id, int minPlayers, int maxPlayers) {
        return isAvailable() ? manager().createMatch(id, minPlayers, maxPlayers) : null;
    }

    /**
     * 删除对局并清理玩家分配记录
     */
    public boolean removeMatch(String id) {
        return isAvailable() && manager().removeMatch(id);
    }

    /**
     * 获取对局
     */
    public Match getMatch(String id) {
        return isAvailable() ? manager().getMatch(id) : null;
    }

    /**
     * 获取全部对局副本
     */
    public List<Match> getMatches() {
        return isAvailable() ? new ArrayList<>(manager().getMatches()) : new ArrayList<Match>();
    }

    /**
     * 获取对局数量
     */
    public int getMatchCount() {
        return isAvailable() ? manager().getMatchCount() : 0;
    }

    /**
     * 强制开始对局（仅等待中的对局可开始，状态切换为 RUNNING 并触发 MatchStartEvent）
     * 会触发 MatchStartEvent 并执行 match.commands.on-start 控制台命令
     */
    public boolean startMatch(String id) {
        if (!isAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("MinigameAPI.startMatch 必须在主线程调用。");
            return false;
        }
        return manager().forceStart(id);
    }

    /**
     * 结束对局（默认结束原因 MANUAL；状态切换为 ENDED，
     * 触发 MatchEndEvent 并执行 match.commands.on-end 控制台命令）
     */
    public boolean endMatch(String id) {
        return endMatch(id, "MANUAL");
    }

    /**
     * 结束对局（指定结束原因，会传入命令占位符 {reason}）
     */
    public boolean endMatch(String id, String reason) {
        if (!isAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("MinigameAPI.endMatch 必须在主线程调用。");
            return false;
        }
        return manager().endMatch(id, reason);
    }

    /**
     * 设置对局状态
     */
    public boolean setMatchState(String id, MatchState state) {
        return isAvailable() && state != null && manager().setState(id, state);
    }

    /**
     * 设置手动人数（传 null 取消手动模式，恢复按已分配玩家数自动统计）
     */
    public boolean setManualPlayers(String id, Integer players) {
        return isAvailable() && manager().setManualPlayers(id, players);
    }

    // ==================== 对局玩家查询 ====================

    /**
     * 获取对局当前人数（手动模式下为手动人数）
     */
    public int getPlayers(String matchId) {
        Match match = getMatch(matchId);
        return match != null ? match.getPlayers() : 0;
    }

    /**
     * 对局是否还有空位（等待中且未满员）
     */
    public boolean hasFreeSlot(String matchId) {
        Match match = getMatch(matchId);
        return match != null && match.hasFreeSlot();
    }

    /**
     * 获取对局中全部已分配玩家（含等待连入与已连入）
     */
    public List<UUID> getAssignedPlayers(String matchId) {
        return isAvailable() ? manager().getAssignedUuids(matchId) : new ArrayList<UUID>();
    }

    /**
     * 获取对局中等待中的玩家（已被登录服放行分配、但对局尚未连入）
     */
    public List<UUID> getWaitingPlayers(String matchId) {
        return isAvailable() ? manager().getWaitingUuids(matchId) : new ArrayList<UUID>();
    }

    /**
     * 获取对局中已连入服务器的玩家
     */
    public List<UUID> getConnectedPlayers(String matchId) {
        return isAvailable() ? manager().getConnectedUuids(matchId) : new ArrayList<UUID>();
    }

    // ==================== 玩家归属查询 ====================

    /**
     * 获取玩家当前被分配到的对局 ID（未分配时返回 null）
     */
    public String getMatchId(UUID uuid) {
        return isAvailable() ? manager().getAssignedMatchId(uuid) : null;
    }

    /**
     * 玩家是否已被分配到某个对局
     */
    public boolean isAssigned(UUID uuid) {
        return getMatchId(uuid) != null;
    }

    /**
     * 玩家是否已连入其被分配的对局所在服务器
     */
    public boolean isConnected(UUID uuid) {
        String matchId = getMatchId(uuid);
        if (matchId == null) {
            return false;
        }
        Match match = getMatch(matchId);
        if (match == null) {
            return false;
        }
        Match.AssignedPlayer assigned = match.getAssignedPlayers().get(uuid);
        return assigned != null && assigned.isConnected();
    }

    // ==================== 主动分配（须在主线程调用）====================

    /**
     * 将本服玩家直接分配到指定对局（不经过登录服排队）
     * 若玩家已分配至其他对局，会先将其从原对局移除
     * 会触发 MatchAssignEvent
     *
     * @return 是否成功（对局不存在或非主线程调用时返回 false）
     */
    public boolean assignPlayer(UUID uuid, String matchId) {
        if (!isAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("MinigameAPI.assignPlayer 必须在主线程调用。");
            return false;
        }
        return manager().assignPlayerDirectly(uuid, matchId);
    }

    /**
     * 将玩家从其对局分配中移除
     *
     * @return 玩家原本是否已分配
     */
    public boolean unassignPlayer(UUID uuid) {
        if (!isAvailable()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("MinigameAPI.unassignPlayer 必须在主线程调用。");
            return false;
        }
        return manager().unassignPlayer(uuid);
    }

    // ==================== 登录服排队队列（缓存同步）====================

    /**
     * 获取登录服排队队列总人数（最近一次同步的缓存值）
     */
    public int getQueueSize() {
        return plugin.getQueueSizeCache();
    }

    /**
     * 获取登录服排队队列中的玩家 UUID 列表（最多 100 个，最近一次同步的缓存值）
     */
    public List<UUID> getQueuePlayers() {
        return plugin.getQueuePlayersCache();
    }

    /**
     * 队列信息缓存的时间戳（毫秒），0 表示从未同步
     */
    public long getQueueInfoTimestamp() {
        return plugin.getQueueInfoTimestamp();
    }

    /**
     * 主动请求主插件刷新排队队列信息（异步，结果更新到缓存）
     */
    public void refreshQueueInfo() {
        plugin.requestQueueInfoRefresh();
    }

    // ==================== 请求放行 ====================

    /**
     * 请求登录服将排队中的指定玩家放行进入指定对局（异步）
     * 玩家放行后将通过代理跳转到本服，连入后触发 MatchAssignEvent
     * 实际放行结果可通过玩家是否连入（isConnected）判断
     *
     * @param playerUuid 要放行的玩家 UUID（必须正在登录服排队）
     * @param matchId    目标对局 ID（须为本服已创建的对局）
     * @return 请求是否已发送（不代表放行成功）
     */
    public boolean requestRelease(UUID playerUuid, String matchId) {
        if (playerUuid == null || matchId == null) {
            return false;
        }
        return plugin.requestPlayerRelease(playerUuid, matchId);
    }
}
