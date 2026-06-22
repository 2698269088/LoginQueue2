package top.mcocet.loginsequence2limbo.listener;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.EventPriority;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerInteractEvent;
import com.loohp.limbo.events.player.PlayerMoveEvent;
import com.loohp.limbo.location.Location;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.world.World;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;

import java.util.Set;
import java.util.UUID;

public class PlayerRestrictionListener implements Listener {

    private final LoginSequence2Limbo plugin;
    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;
    private final Location spawnCenter;
    private final double protectionRadius;

    public PlayerRestrictionListener(LoginSequence2Limbo plugin, PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers) {
        this.plugin = plugin;
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;

        String worldName = plugin.getConfigValueString("queue.spawn.world", "world");
        World world = Limbo.getInstance().getWorld(worldName);
        if (world == null && !Limbo.getInstance().getWorlds().isEmpty()) {
            world = Limbo.getInstance().getWorlds().get(0);
        }
        double centerX = plugin.getConfigValueDouble("queue.spawn.x", 0.0);
        double centerY = plugin.getConfigValueDouble("queue.spawn.y", 64.0);
        double centerZ = plugin.getConfigValueDouble("queue.spawn.z", 0.0);
        this.spawnCenter = new Location(world, centerX, centerY, centerZ);
        this.protectionRadius = Math.max(0, plugin.getConfigValueDouble("queue.spawn-protection-radius", 0.0));
    }

    private boolean isInProtectionArea(Location location) {
        if (spawnCenter.getWorld() == null || location.getWorld() == null) {
            return false;
        }
        if (!spawnCenter.getWorld().equals(location.getWorld())) {
            return false;
        }
        if (protectionRadius <= 0) {
            return true;
        }
        return location.distance(spawnCenter) <= protectionRadius;
    }

    private boolean isRestricted(Player player) {
        if (allowedPlayers.contains(player.getUniqueId())) {
            return false;
        }

        boolean adminBypass = plugin.getConfigValueBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginsequence.admin.bypass")) {
            return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfigValueBoolean("queue.restrict-movement", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        // 只拦截位置变化（忽略视角转动）
        if (Location.locToBlock(event.getFrom().getX()) == Location.locToBlock(event.getTo().getX())
                && Location.locToBlock(event.getFrom().getY()) == Location.locToBlock(event.getTo().getY())
                && Location.locToBlock(event.getFrom().getZ()) == Location.locToBlock(event.getTo().getZ())) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getConfigValueBoolean("queue.allow-block-interact", false)) {
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
}
