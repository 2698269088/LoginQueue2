package top.mcocet.loginsequence2.listener;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

public class PerformanceListener implements Listener {

    private final boolean performanceMode;

    public PerformanceListener(boolean performanceMode) {
        this.performanceMode = performanceMode;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!performanceMode) {
            return;
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
