package top.mcocet.loginsequence2.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import top.mcocet.loginsequence2.LoginSequence;

import java.util.Set;
import java.util.UUID;

public class PlayerRestrictionListener implements Listener {

    private final LoginSequence plugin;
    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;

    public PlayerRestrictionListener(LoginSequence plugin, PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers) {
        this.plugin = plugin;
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;
    }

    private boolean isRestricted(Player player) {
        if (allowedPlayers.contains(player.getUniqueId())) {
            return false;
        }

        boolean adminBypass = plugin.getConfig().getBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginsequence.admin.bypass")) {
            return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("queue.restrict-movement", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        // 只拦截位置变化（忽略视角转动）
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getConfig().getBoolean("queue.allow-block-interact", false)) {
            return;
        }

        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        if (event.getClickedBlock() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getConfig().getBoolean("queue.allow-block-place", false)) {
            return;
        }

        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getConfig().getBoolean("queue.allow-block-break", false)) {
            return;
        }

        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }

        Player player = (Player) entity;
        if (!isRestricted(player)) {
            return;
        }

        // 阻止未放行玩家受到伤害
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player)) {
            return;
        }

        Player player = (Player) damager;
        if (!isRestricted(player)) {
            return;
        }

        // 阻止未放行玩家攻击其他实体
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        if (!isRestricted(player)) {
            return;
        }

        event.setCancelled(true);
    }
}
