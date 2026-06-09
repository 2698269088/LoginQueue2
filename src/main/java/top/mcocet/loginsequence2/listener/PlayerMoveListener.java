package top.mcocet.loginsequence2.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Set;
import java.util.UUID;

public class PlayerMoveListener implements Listener {

    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;
    private final boolean restrictMovement;

    public PlayerMoveListener(PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers, boolean restrictMovement) {
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;
        this.restrictMovement = restrictMovement;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!restrictMovement) {
            return;
        }

        // 只拦截位置变化（忽略视角转动）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 已放行的玩家不限制
        if (allowedPlayers.contains(uuid)) {
            return;
        }

        // 限制所有未放行玩家的移动（无论是否在队列中）
        event.setCancelled(true);
    }
}
