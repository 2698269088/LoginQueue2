package top.mcocet.loginqueue2.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import top.mcocet.loginqueue2.LoginQueue2;

import java.util.Set;
import java.util.UUID;

public class PlayerMoveListener implements Listener {

    private final LoginQueue2 plugin;
    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;
    private final boolean restrictMovement;
    private final boolean restrictRange;
    private final double rangeLimit;
    private final Location centerLocation;

    public PlayerMoveListener(LoginQueue2 plugin, PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers, boolean restrictMovement) {
        this.plugin = plugin;
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;
        this.restrictMovement = restrictMovement;

        FileConfiguration config = plugin.getConfig();
        this.restrictRange = config.getBoolean("queue.restrict-range", false);
        this.rangeLimit = Math.max(0, config.getDouble("queue.range-limit", 10.0));

        String worldName = config.getString("queue.spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null && !plugin.getServer().getWorlds().isEmpty()) {
            world = plugin.getServer().getWorlds().get(0);
        }
        double centerX = config.getDouble("queue.spawn.x", 0.0);
        double centerY = config.getDouble("queue.spawn.y", 64.0);
        double centerZ = config.getDouble("queue.spawn.z", 0.0);
        float pitch = (float) config.getDouble("queue.spawn.pitch", 0.0);
        float yaw = (float) config.getDouble("queue.spawn.yaw", 0.0);
        this.centerLocation = new Location(world, centerX, centerY, centerZ, yaw, pitch);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 只处理位置变化（忽略视角转动）
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
