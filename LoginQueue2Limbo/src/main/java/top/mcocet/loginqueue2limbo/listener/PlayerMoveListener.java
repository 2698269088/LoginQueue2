package top.mcocet.loginqueue2limbo.listener;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.EventPriority;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerMoveEvent;
import com.loohp.limbo.location.Location;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.world.World;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;

import java.util.Set;
import java.util.UUID;

public class PlayerMoveListener implements Listener {

    private final LoginQueue2Limbo plugin;
    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;
    private final boolean restrictMovement;
    private final boolean restrictRange;
    private final double rangeLimit;
    private final Location centerLocation;

    public PlayerMoveListener(LoginQueue2Limbo plugin, PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers, boolean restrictMovement) {
        this.plugin = plugin;
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;
        this.restrictMovement = restrictMovement;

        this.restrictRange = plugin.getConfigValueBoolean("queue.restrict-range", false);
        this.rangeLimit = Math.max(0, plugin.getConfigValueDouble("queue.range-limit", 10.0));

        String worldName = plugin.getConfigValueString("queue.spawn.world", "world");
        World world = Limbo.getInstance().getWorld(worldName);
        if (world == null && !Limbo.getInstance().getWorlds().isEmpty()) {
            world = Limbo.getInstance().getWorlds().get(0);
        }
        double centerX = plugin.getConfigValueDouble("queue.spawn.x", 0.0);
        double centerY = plugin.getConfigValueDouble("queue.spawn.y", 64.0);
        double centerZ = plugin.getConfigValueDouble("queue.spawn.z", 0.0);
        float pitch = (float) plugin.getConfigValueDouble("queue.spawn.pitch", 0.0);
        float yaw = (float) plugin.getConfigValueDouble("queue.spawn.yaw", 0.0);
        this.centerLocation = new Location(world, centerX, centerY, centerZ, yaw, pitch);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 只处理位置变化（忽略视角转动）
        if (Location.locToBlock(event.getFrom().getX()) == Location.locToBlock(event.getTo().getX())
                && Location.locToBlock(event.getFrom().getY()) == Location.locToBlock(event.getTo().getY())
                && Location.locToBlock(event.getFrom().getZ()) == Location.locToBlock(event.getTo().getZ())) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 已放行的玩家不限制
        if (allowedPlayers.contains(uuid)) {
            return;
        }

        // 限制移动
        if (restrictMovement) {
            event.setCancelled(true);
            return;
        }

        // 限制活动范围
        if (restrictRange && centerLocation.getWorld() != null) {
            Location to = event.getTo();
            if (!to.getWorld().equals(centerLocation.getWorld())) {
                player.teleport(centerLocation);
                event.setCancelled(true);
                return;
            }
            double distance = to.distance(centerLocation);
            if (distance > rangeLimit) {
                Location safeLocation = pullBackLocation(to, centerLocation, rangeLimit);
                player.teleport(safeLocation);
                event.setCancelled(true);
            }
        }
    }

    private Location pullBackLocation(Location current, Location center, double limit) {
        double dx = current.getX() - center.getX();
        double dy = current.getY() - center.getY();
        double dz = current.getZ() - center.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0) {
            return center.clone();
        }
        double ratio = limit / distance;
        double newX = center.getX() + dx * ratio;
        double newY = center.getY() + dy * ratio;
        double newZ = center.getZ() + dz * ratio;
        return new Location(center.getWorld(), newX, newY, newZ, current.getYaw(), current.getPitch());
    }
}
