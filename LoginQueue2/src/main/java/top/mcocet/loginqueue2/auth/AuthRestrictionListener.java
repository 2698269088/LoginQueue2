package top.mcocet.loginqueue2.auth;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import top.mcocet.loginqueue2.LoginQueue2;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 未认证玩家的限制监听器
 * 未登录/未注册的玩家会被限制移动和交互
 */
public class AuthRestrictionListener implements Listener {

    private final LoginQueue2 plugin;
    private final AuthManager authManager;
    private final Set<UUID> authenticatedPlayers = new HashSet<>();
    private final Set<String> allowedCommands = new HashSet<>();

    public AuthRestrictionListener(LoginQueue2 plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        allowedCommands.add("/login");
        allowedCommands.add("/register");
        allowedCommands.add("/changepassword");
        allowedCommands.add("/changepw");
        allowedCommands.add("/logseq");
        allowedCommands.add("/ls");
        allowedCommands.add("/server");
        allowedCommands.add("/join");
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
        if (player.hasPermission("loginqueue2.admin.bypass")) return false;
        return !authenticatedPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!authManager.isEnabled()) return;
        Player player = event.getPlayer();
        if (!isRestricted(player)) return;

        // 只拦截位置变化（忽略视角转动）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!authManager.isEnabled()) return;
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (isRestricted(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!authManager.isEnabled()) return;
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getLanguageManager().getMessage("auth-chat-restricted"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!authManager.isEnabled()) return;
        Player player = event.getPlayer();
        if (isRestricted(player)) {
            String cmd = event.getMessage().split(" ")[0].toLowerCase();
            if (!allowedCommands.contains(cmd)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getLanguageManager().getMessage("auth-command-restricted"));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!authManager.isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!authManager.isEnabled()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }
}
