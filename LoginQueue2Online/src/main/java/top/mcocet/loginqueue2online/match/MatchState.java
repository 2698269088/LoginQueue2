package top.mcocet.loginqueue2online.match;

/**
 * 对局状态
 */
public enum MatchState {
    /** 等待中（等待玩家加入，主插件可继续放行玩家补位） */
    WAITING,
    /** 进行中（游戏已开始，主插件不再向该对局放行玩家） */
    RUNNING,
    /** 已结束（不再接受放行；可重置为 WAITING 后重新开始） */
    ENDED
}
