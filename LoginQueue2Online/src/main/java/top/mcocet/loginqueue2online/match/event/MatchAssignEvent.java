package top.mcocet.loginqueue2online.match.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * 玩家被分配进入对局事件
 * 当主插件（登录服）排队放行玩家到本服对局时触发
 */
public class MatchAssignEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final String matchId;

    public MatchAssignEvent(UUID playerUuid, String matchId) {
        this.playerUuid = playerUuid;
        this.matchId = matchId;
    }

    /**
     * 被分配玩家的 UUID
     */
    public UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * 目标对局 ID
     */
    public String getMatchId() {
        return matchId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
