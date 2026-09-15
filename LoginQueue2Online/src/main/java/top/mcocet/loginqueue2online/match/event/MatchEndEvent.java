package top.mcocet.loginqueue2online.match.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 对局结束事件
 * 当对局状态切换为已结束时触发（命令、API 或主插件通知）
 */
public class MatchEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String matchId;
    private final String reason;
    private final List<UUID> playerUuids;

    public MatchEndEvent(String matchId, String reason, List<UUID> playerUuids) {
        this.matchId = matchId;
        this.reason = reason;
        this.playerUuids = playerUuids != null ? new ArrayList<>(playerUuids) : new ArrayList<UUID>();
    }

    /**
     * 对局 ID
     */
    public String getMatchId() {
        return matchId;
    }

    /**
     * 结束原因（MANUAL 手动结束等）
     */
    public String getReason() {
        return reason;
    }

    /**
     * 结束时对局内的玩家 UUID 快照
     */
    public List<UUID> getPlayerUuids() {
        return new ArrayList<>(playerUuids);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
