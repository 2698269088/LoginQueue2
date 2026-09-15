package top.mcocet.loginqueue2online.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小游戏对局
 * 由 /lq2match 命令创建与管理，玩家由主插件（登录服）排队放行后分配进入
 */
public class Match {

    private final String id;
    private final long createdAt;
    private volatile MatchState state = MatchState.WAITING;
    private volatile int minPlayers;
    private volatile int maxPlayers;
    // 手动指定人数（null 表示按已分配玩家数自动统计）
    private volatile Integer manualPlayers = null;
    // 已分配玩家: UUID -> 分配信息
    private final Map<UUID, AssignedPlayer> assignedPlayers = new ConcurrentHashMap<>();
    private volatile long startedAt = 0L;
    private volatile String startReason = null;
    private volatile long endedAt = 0L;
    private volatile String endReason = null;

    public Match(String id, int minPlayers, int maxPlayers) {
        this.id = id;
        this.minPlayers = Math.max(1, minPlayers);
        this.maxPlayers = Math.max(this.minPlayers, maxPlayers);
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public MatchState getState() {
        return state;
    }

    public void setState(MatchState state) {
        this.state = state;
    }

    public boolean isWaiting() {
        return state == MatchState.WAITING;
    }

    public boolean isRunning() {
        return state == MatchState.RUNNING;
    }

    public boolean isEnded() {
        return state == MatchState.ENDED;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = Math.max(1, minPlayers);
        if (maxPlayers < this.minPlayers) {
            this.maxPlayers = this.minPlayers;
        }
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = Math.max(minPlayers, maxPlayers);
    }

    public Integer getManualPlayers() {
        return manualPlayers;
    }

    public void setManualPlayers(Integer manualPlayers) {
        this.manualPlayers = manualPlayers;
    }

    /**
     * 当前对局人数
     * 手动模式下返回手动人数，否则返回已分配玩家数
     */
    public int getPlayers() {
        Integer manual = manualPlayers;
        return manual != null ? manual : assignedPlayers.size();
    }

    /**
     * 是否还能容纳新玩家
     */
    public boolean hasFreeSlot() {
        return isWaiting() && getPlayers() < maxPlayers;
    }

    /**
     * 添加被分配的玩家（主插件放行进入对局）
     *
     * @return 是否成功添加（已存在时返回 false）
     */
    public boolean addAssigned(UUID uuid) {
        return assignedPlayers.putIfAbsent(uuid, new AssignedPlayer(uuid)) == null;
    }

    /**
     * 移除被分配的玩家
     */
    public boolean removeAssigned(UUID uuid) {
        return assignedPlayers.remove(uuid) != null;
    }

    /**
     * 标记玩家已连入服务器
     */
    public void markConnected(UUID uuid) {
        AssignedPlayer assigned = assignedPlayers.get(uuid);
        if (assigned != null) {
            assigned.connected = true;
        }
    }

    /**
     * 清理超时未连入的已分配玩家
     *
     * @param timeoutMillis 分配后允许的连入时间（毫秒）
     * @return 被清理的玩家 UUID 列表
     */
    public List<UUID> cleanupExpired(long timeoutMillis) {
        long now = System.currentTimeMillis();
        List<UUID> removed = new ArrayList<>();
        for (Map.Entry<UUID, AssignedPlayer> entry : assignedPlayers.entrySet()) {
            AssignedPlayer assigned = entry.getValue();
            if (!assigned.connected && now - assigned.assignTime > timeoutMillis) {
                if (assignedPlayers.remove(entry.getKey()) != null) {
                    removed.add(entry.getKey());
                }
            }
        }
        return removed;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public String getStartReason() {
        return startReason;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }

    /**
     * 标记对局已开始
     */
    public void markStarted(String reason) {
        this.state = MatchState.RUNNING;
        this.startedAt = System.currentTimeMillis();
        this.startReason = reason;
    }

    /**
     * 标记对局已结束
     */
    public void markEnded(String reason) {
        this.state = MatchState.ENDED;
        this.endedAt = System.currentTimeMillis();
        this.endReason = reason;
    }

    /**
     * 获取已分配玩家副本
     */
    public Map<UUID, AssignedPlayer> getAssignedPlayers() {
        return new LinkedHashMap<>(assignedPlayers);
    }

    /**
     * 已分配玩家信息
     */
    public static class AssignedPlayer {
        private final UUID uuid;
        private final long assignTime;
        private volatile boolean connected;

        AssignedPlayer(UUID uuid) {
            this.uuid = uuid;
            this.assignTime = System.currentTimeMillis();
        }

        public UUID getUuid() {
            return uuid;
        }

        public long getAssignTime() {
            return assignTime;
        }

        /**
         * 是否已连入服务器（触发过 PlayerJoinEvent）
         */
        public boolean isConnected() {
            return connected;
        }
    }
}
