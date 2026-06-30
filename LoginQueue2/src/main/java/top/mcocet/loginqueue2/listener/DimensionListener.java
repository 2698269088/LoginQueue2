package top.mcocet.loginqueue2.listener;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import top.mcocet.loginqueue2.LoginQueue2;

public class DimensionListener implements Listener {

    private final LoginQueue2 plugin;

    public DimensionListener(LoginQueue2 plugin) {
        this.plugin = plugin;
    }

    private boolean isRestricted(Player player) {
        boolean adminBypass = plugin.getConfig().getBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginqueue2.admin.bypass")) {
            return false;
        }
        return true;
    }

    private boolean isDimensionDisabled(World.Environment environment) {
        if (environment == World.Environment.NETHER) {
            return plugin.getConfig().getBoolean("queue.disable-nether", true);
        }
        if (environment == World.Environment.THE_END) {
            return plugin.getConfig().getBoolean("queue.disable-end", true);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        World toWorld = event.getTo() != null ? event.getTo().getWorld() : null;
        if (toWorld == null) {
            return;
        }

        // 禁用目标维度
        if (isDimensionDisabled(toWorld.getEnvironment())) {
            event.setCancelled(true);
            return;
        }

        // 禁用传送门传送
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (plugin.getConfig().getBoolean("queue.disable-portals", true)
                && (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_PORTAL
                || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        if (plugin.getConfig().getBoolean("queue.disable-portals", true)) {
            event.setCancelled(true);
            return;
        }

        World toWorld = event.getTo() != null ? event.getTo().getWorld() : null;
        if (toWorld != null && isDimensionDisabled(toWorld.getEnvironment())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        if (!isRestricted(player)) {
            return;
        }

        if (plugin.getConfig().getBoolean("queue.disable-portals", true)) {
            event.setCancelled(true);
            return;
        }

        World toWorld = event.getTo() != null ? event.getTo().getWorld() : null;
        if (toWorld != null && isDimensionDisabled(toWorld.getEnvironment())) {
            event.setCancelled(true);
        }
    }
}
