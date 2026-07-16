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
import top.mcocet.loginqueue2.world.LoginWorldManager;

public class DimensionListener implements Listener {

    private final LoginQueue2 plugin;
    private final LoginWorldManager loginWorldManager;

    public DimensionListener(LoginQueue2 plugin, LoginWorldManager loginWorldManager) {
        this.plugin = plugin;
        this.loginWorldManager = loginWorldManager;
    }

    private boolean isRestricted(Player player) {
        // WORLD 模式下，已放行玩家不受限制
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            if (plugin.getLoginWorldManager() != null) {
                // 检查玩家是否已放行
                for (PlayerJoinListener listener : getPlayerJoinListeners()) {
                    if (listener.getAllowedPlayers().contains(player.getUniqueId())) {
                        return false;
                    }
                }
            }
        }

        boolean adminBypass = plugin.getConfig().getBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginqueue2.admin.bypass")) {
            return false;
        }
        return true;
    }

    private java.util.List<PlayerJoinListener> getPlayerJoinListeners() {
        return org.bukkit.event.HandlerList.getRegisteredListeners(plugin).stream()
                .map(org.bukkit.plugin.RegisteredListener::getListener)
                .filter(l -> l instanceof PlayerJoinListener)
                .map(l -> (PlayerJoinListener) l)
                .collect(java.util.stream.Collectors.toList());
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

        // WORLD 模式下，允许从登录世界传送到主世界，禁止其他传送
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            World loginWorld = loginWorldManager.getLoginWorld();
            if (loginWorld != null && loginWorld.equals(player.getWorld())) {
                // 玩家在登录世界，只允许传送到主世界
                String mainWorldName = plugin.getConfig().getString("queue.spawn.world", "world");
                World mainWorld = plugin.getServer().getWorld(mainWorldName);
                if (mainWorld != null && toWorld.equals(mainWorld)) {
                    return; // 允许传送到主世界
                }
                // 其他传送一律禁止
                event.setCancelled(true);
                return;
            }
            // 不在登录世界，不限制
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

        // WORLD 模式下，登录世界禁止传送门
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            if (loginWorldManager.isInLoginWorld(player)) {
                event.setCancelled(true);
                return;
            }
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

        // WORLD 模式下，登录世界禁止传送门
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            if (loginWorldManager.isInLoginWorld(player)) {
                event.setCancelled(true);
                return;
            }
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
