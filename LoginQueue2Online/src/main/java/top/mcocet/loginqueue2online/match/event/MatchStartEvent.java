package top.mcocet.loginqueue2online.match.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 对局开始事件
 * 当对局状态切换为进行中时触发（手动开始或主插件凑齐超时通知）
 */
public class MatchStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String matchId;
    private final String reason;

    public MatchStartEvent(String matchId, String reason) {
        this.matchId = matchId;
        this.reason = reason;
    }

    /**
     * 对局 ID
     */
    public String getMatchId() {
        return matchId;
    }

    /**
     * 开始原因（MANUAL 手动开始，TIMEOUT 主插件凑齐超时通知）
     */
    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
