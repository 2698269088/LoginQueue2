package top.mcocet.loginsequence2limbo.auth;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.EventPriority;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.inventory.InventoryClickEvent;
import com.loohp.limbo.events.inventory.InventoryDragEvent;
import com.loohp.limbo.events.player.PlayerInteractEvent;
import com.loohp.limbo.events.player.PlayerMoveEvent;
import com.loohp.limbo.player.Player;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 未认证玩家的限制监听器（Limbo 版本）
 */
public class AuthRestrictionListener implements Listener {

    private final LoginSequence2Limbo plugin;
    private final AuthManager authManager;
    private final Set<UUID> authenticatedPlayers = new HashSet<>();

    public AuthRestrictionListener(LoginSequence2Limbo plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    public boolean isAuthenticated(UUID uuid) {
        return !authManager.isEnabled() || authenticatedPlayers.contains(uuid);
    }

    public void setAuthenticated(UUID uuid) {
        authenticatedPlayers.add(uuid);
    }

    public void removeAuthenticated(UUID uuid) {
        authenticatedPlayers.remove(uuid);
    }

    private boolean isRestricted(Player player) {
        if (!authManager.isEnabled()) return false;
        if (player.hasPermission("loginsequence.admin.bypass")) return false;
        return !authenticatedPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!authManager.isEnabled()) return;
        Player player = event.getPlayer();
        if (!isRestricted(player)) return;

        // 只拦截位置变化（忽略视角转动）
        if (com.loohp.limbo.location.Location.locToBlock(event.getFrom().getX()) == com.loohp.limbo.location.Location.locToBlock(event.getTo().getX())
                && com.loohp.limbo.location.Location.locToBlock(event.getFrom().getY()) == com.loohp.limbo.location.Location.locToBlock(event.getTo().getY())
                && com.loohp.limbo.location.Location.locToBlock(event.getFrom().getZ()) == com.loohp.limbo.location.Location.locToBlock(event.getTo().getZ())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!authManager.isEnabled()) return;
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!authManager.isEnabled()) return;
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }
}
