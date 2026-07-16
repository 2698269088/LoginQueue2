package top.mcocet.loginqueue2.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class PlayerRestrictionListener implements Listener {

    private final LoginQueue2 plugin;
    private final PlayerJoinListener playerJoinListener;
    private final Set<UUID> allowedPlayers;
    private final Location spawnCenter;
    private final double protectionRadius;
    private final Set<String> worldModeAllowedCommands = new HashSet<>();

    public PlayerRestrictionListener(LoginQueue2 plugin, PlayerJoinListener playerJoinListener, Set<UUID> allowedPlayers) {
        this.plugin = plugin;
        this.playerJoinListener = playerJoinListener;
        this.allowedPlayers = allowedPlayers;

        String worldName = plugin.getConfig().getString("queue.spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null && !plugin.getServer().getWorlds().isEmpty()) {
            world = plugin.getServer().getWorlds().get(0);
        }
        double centerX = plugin.getConfig().getDouble("queue.spawn.x", 0.0);
        double centerY = plugin.getConfig().getDouble("queue.spawn.y", 64.0);
        double centerZ = plugin.getConfig().getDouble("queue.spawn.z", 0.0);
        this.spawnCenter = new Location(world, centerX, centerY, centerZ);
        this.protectionRadius = Math.max(0, plugin.getConfig().getDouble("queue.spawn-protection-radius", 0.0));
        loadWorldModeAllowedCommands();
    }

    public void loadWorldModeAllowedCommands() {
        worldModeAllowedCommands.clear();
        // 默认允许的基础命令
        worldModeAllowedCommands.add("/join");
        worldModeAllowedCommands.add("/logseq");
        worldModeAllowedCommands.add("/ls");
        // 从配置文件加载额外命令
        List<String> configCommands = plugin.getConfig().getStringList("world-mode.allowed-commands");
        for (String cmd : configCommands) {
            String normalized = cmd.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            if (!normalized.isEmpty()) {
                worldModeAllowedCommands.add(normalized);
            }
        }
    }

    private boolean isInLoginWorldRestricted(Player player) {
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager == null || !loginWorldManager.isWorldMode()) {
            return false;
        }
        // 只有在登录世界且未被放行的玩家才受限制
        if (!loginWorldManager.isInLoginWorld(player)) {
            return false;
        }
        if (allowedPlayers.contains(player.getUniqueId())) {
            return false;
        }
        boolean adminBypass = plugin.getConfig().getBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginqueue2.admin.bypass")) {
            return false;
        }
        return true;
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
        // WORLD 模式下，不在登录世界的玩家不受限制
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            if (!loginWorldManager.isInLoginWorld(player)) {
                return false;
            }
        }

        if (allowedPlayers.contains(player.getUniqueId())) {
            return false;
        }

        boolean adminBypass = plugin.getConfig().getBoolean("queue.admin-bypass", true);
        if (adminBypass && player.hasPermission("loginqueue2.admin.bypass")) {
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
        if (!plugin.getConfig().getBoolean("queue.spawn-protection", true)) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }

        if (!isInProtectionArea(entity.getLocation())) {
            return;
        }

        // 登录点保护：取消保护区域内玩家受到的伤害
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("queue.spawn-protection", true)) {
            return;
        }

        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        // 登录点保护：禁止保护区域内的 PVP（玩家攻击玩家）
        if (damager instanceof Player && victim instanceof Player) {
            if (isInProtectionArea(damager.getLocation()) || isInProtectionArea(victim.getLocation())) {
                event.setCancelled(true);
            }
            return;
        }

        // 仍保留原有逻辑：未放行玩家不能攻击其他实体
        if (damager instanceof Player) {
            Player player = (Player) damager;
            if (isRestricted(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("queue.spawn-protection", true)) {
            return;
        }

        // 登录点保护：禁止保护区域内的爆炸破坏方块
        if (isInProtectionArea(event.getLocation())) {
            event.blockList().clear();
        }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isInLoginWorldRestricted(player)) {
            return;
        }
        String cmd = event.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (!worldModeAllowedCommands.contains(cmd)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().getMessage("world-mode-command-restricted"));
        }
    }
}
