package top.mcocet.loginqueue2.listener;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import top.mcocet.loginqueue2.world.LoginWorldManager;

public class PerformanceListener implements Listener {

    private final boolean performanceMode;
    private final LoginWorldManager loginWorldManager;

    public PerformanceListener(boolean performanceMode, LoginWorldManager loginWorldManager) {
        this.performanceMode = performanceMode;
        this.loginWorldManager = loginWorldManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!performanceMode) {
            return;
        }

        // WORLD 模式下只限制登录世界
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            World loginWorld = loginWorldManager.getLoginWorld();
            if (loginWorld == null || !loginWorld.equals(event.getEntity().getWorld())) {
                return;
            }
        }

        // 禁用生物自然生成和刷怪笼生成等
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!performanceMode) {
            return;
        }

        // WORLD 模式下只限制登录世界
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            World loginWorld = loginWorldManager.getLoginWorld();
            if (loginWorld == null || !loginWorld.equals(event.getWorld())) {
                return;
            }
        }

        // 禁用天气更替
        event.setCancelled(true);
    }

    /**
     * 对世界应用性能优化设置（禁用时间流逝）
     */
    public static void applyWorldSettings(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
    }
}
