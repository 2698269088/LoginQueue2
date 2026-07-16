package top.mcocet.loginqueue2.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.TimeUnit;

/**
 * Folia 兼容的调度器工具类
 * 自动检测服务器类型（Paper/Folia）并使用对应的调度 API
 */
public class SchedulerUtil {

    private static Boolean isFolia = null;

    /**
     * 检测当前服务器是否在 Folia 上运行
     */
    public static boolean isFolia() {
        if (isFolia != null) {
            return isFolia;
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }
        return isFolia;
    }

    /**
     * 在全局区域上执行延迟任务
     * Paper: 使用 BukkitScheduler.runTaskLater
     * Folia: 使用 GlobalRegionScheduler.execute + 延迟
     */
    public static void runTaskLater(JavaPlugin plugin, Runnable task, long delayTicks) {
        if (isFolia()) {
            // Folia: 使用 GlobalRegionScheduler
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                // Folia 的 execute 是立即执行，需要额外处理延迟
                // 使用 delayed task
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
            });
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskLater(plugin, delayTicks);
        }
    }

    /**
     * 在全局区域上执行定时任务
     * Paper: 使用 BukkitScheduler.runTaskTimer
     * Folia: 使用 GlobalRegionScheduler.runAtFixedRate
     */
    public static void runTaskTimer(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delayTicks, periodTicks);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskTimer(plugin, delayTicks, periodTicks);
        }
    }

    /**
     * 在全局区域上立即执行任务
     * Paper: 使用 BukkitScheduler.runTask
     * Folia: 使用 GlobalRegionScheduler.execute
     */
    public static void runTask(JavaPlugin plugin, Runnable task) {
        if (isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTask(plugin);
        }
    }

    /**
     * 在玩家所在区域上执行延迟任务
     * Paper: 使用 BukkitScheduler.runTaskLater
     * Folia: 使用 EntityScheduler.runDelayed
     */
    public static void runPlayerTaskLater(org.bukkit.entity.Player player, JavaPlugin plugin, Runnable task, long delayTicks) {
        if (isFolia()) {
            player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskLater(plugin, delayTicks);
        }
    }

    /**
     * 在玩家所在区域上执行定时任务
     * Paper: 使用 BukkitScheduler.runTaskTimer
     * Folia: 使用 EntityScheduler.runAtFixedRate
     */
    public static void runPlayerTaskTimer(org.bukkit.entity.Player player, JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia()) {
            player.getScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), null, delayTicks, periodTicks);
        } else {
            new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskTimer(plugin, delayTicks, periodTicks);
        }
    }

    /**
     * 取消所有由该插件注册的全局任务
     * Paper: 使用 BukkitScheduler.cancelTasks
     * Folia: 使用 GlobalRegionScheduler.cancelTasks
     */
    public static void cancelTasks(JavaPlugin plugin) {
        if (isFolia()) {
            plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        } else {
            plugin.getServer().getScheduler().cancelTasks(plugin);
        }
    }

    /**
     * Folia 兼容的玩家传送
     * Paper: 使用 player.teleport
     * Folia: 使用 player.teleportAsync（带回调）
     */
    public static void teleport(Player player, Location location, Runnable onComplete) {
        if (isFolia()) {
            player.teleportAsync(location).thenAccept(success -> {
                if (success && onComplete != null) {
                    onComplete.run();
                }
            });
        } else {
            boolean success = player.teleport(location);
            if (success && onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * Folia 兼容的玩家传送（无回调）
     * Paper: 使用 player.teleport
     * Folia: 使用 player.teleportAsync
     */
    public static void teleport(Player player, Location location) {
        teleport(player, location, null);
    }
}
