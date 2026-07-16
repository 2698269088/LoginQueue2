package top.mcocet.loginqueue2.world;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.util.SchedulerUtil;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登录世界管理器（反射实现）
 * 负责在 WORLD 模式下创建和管理登录世界
 * 使用反射调用 Worlds 插件 API，避免编译期依赖高版本 Java 编译的类
 */
public class LoginWorldManager implements Listener {

    private final LoginQueue2 plugin;
    private final AtomicBoolean creating = new AtomicBoolean(false);
    private volatile World loginWorld;
    private volatile boolean ready = false;

    // 反射缓存
    private Class<?> worldsAccessClass;
    private Class<?> levelClass;
    private Class<?> levelBuilderClass;
    private Class<?> dimensionClass;
    private Class<?> generatorTypeClass;
    private Class<?> worldRegistryClass;
    private Class<?> keyClass;
    private Method keyMethod;
    private Method keyNamespaceMethod;
    private Method keyValueMethod;
    private Method worldsAccessMethod;
    private Method loadMethod;
    private Method createMethod;
    private Method getWorldRegistryMethod;
    private Method registryGetMethod;
    private Method registryRegisterMethod;
    private Method levelBuilderMethod;
    private Method builderDimensionMethod;
    private Method builderGeneratorTypeMethod;
    private Method builderSeedMethod;
    private Method builderStructuresMethod;
    private Method builderBonusChestMethod;
    private Method builderHardcoreMethod;
    private Method builderBuildMethod;
    private boolean reflectionReady = false;

    public LoginWorldManager(LoginQueue2 plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化反射缓存
     */
    private void initReflection() {
        try {
            worldsAccessClass = Class.forName("net.thenextlvl.worlds.WorldsAccess");
            levelClass = Class.forName("net.thenextlvl.worlds.Level");
            levelBuilderClass = Class.forName("net.thenextlvl.worlds.Level$Builder");
            dimensionClass = Class.forName("net.thenextlvl.worlds.Dimension");
            generatorTypeClass = Class.forName("net.thenextlvl.worlds.generator.GeneratorType");
            worldRegistryClass = Class.forName("net.thenextlvl.worlds.WorldRegistry");
            keyClass = Class.forName("net.kyori.adventure.key.Key");

            keyMethod = keyClass.getMethod("key", String.class);
            keyNamespaceMethod = keyClass.getMethod("namespace");
            keyValueMethod = keyClass.getMethod("value");

            worldsAccessMethod = worldsAccessClass.getMethod("access");
            loadMethod = worldsAccessClass.getMethod("load", keyClass);
            createMethod = worldsAccessClass.getMethod("create", levelClass);
            getWorldRegistryMethod = worldsAccessClass.getMethod("getWorldRegistry");

            registryGetMethod = worldRegistryClass.getMethod("get", keyClass);
            registryRegisterMethod = worldRegistryClass.getMethod("register", levelClass, boolean.class);

            levelBuilderMethod = levelClass.getMethod("builder", keyClass);
            builderDimensionMethod = levelBuilderClass.getMethod("dimension", dimensionClass);
            builderGeneratorTypeMethod = levelBuilderClass.getMethod("generatorType", generatorTypeClass);
            builderSeedMethod = levelBuilderClass.getMethod("seed", Long.class);
            builderStructuresMethod = levelBuilderClass.getMethod("structures", Boolean.class);
            builderBonusChestMethod = levelBuilderClass.getMethod("bonusChest", Boolean.class);
            builderHardcoreMethod = levelBuilderClass.getMethod("hardcore", Boolean.class);
            builderBuildMethod = levelBuilderClass.getMethod("build");

            reflectionReady = true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize Worlds API reflection: " + e.getMessage());
            reflectionReady = false;
        }
    }

    /**
     * 初始化登录世界
     * 如果世界已存在则加载，否则创建
     */
    public void init() {
        if (!isWorldMode()) {
            return;
        }

        if (!reflectionReady) {
            initReflection();
        }

        if (!reflectionReady) {
            plugin.getLogger().warning("Worlds API reflection not ready, cannot create login world");
            return;
        }

        FileConfiguration config = plugin.getConfig();
        String keyString = config.getString("login-world.key", "loginqueue2:lobby");
        Object worldKey;
        try {
            worldKey = keyMethod.invoke(null, keyString);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid login world key: " + keyString + ", using default");
            try {
                worldKey = keyMethod.invoke(null, "loginqueue2:lobby");
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to create default world key: " + ex.getMessage());
                return;
            }
        }
        final Object finalWorldKey = worldKey;

        // 所有世界相关操作必须在 Folia 全局区域上执行
        SchedulerUtil.runTask(plugin, () -> initWorldsApi(finalWorldKey));
    }

    /**
     * 在全局区域上执行 Worlds API 调用
     */
    private void initWorldsApi(Object finalWorldKey) {
        // 检查世界是否已加载
        try {
            String namespace = (String) keyNamespaceMethod.invoke(finalWorldKey);
            String value = (String) keyValueMethod.invoke(finalWorldKey);
            NamespacedKey namespacedKey = new NamespacedKey(namespace, value);
            World existing = plugin.getServer().getWorld(namespacedKey);
            if (existing != null) {
                this.loginWorld = existing;
                this.ready = true;
                applyWorldSettings(existing);
                plugin.getLogger().info("Login world already loaded: " + namespace + ":" + value);
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check existing world: " + e.getMessage());
        }

        // 尝试通过 Worlds API 加载
        Object access = getWorldsAccess();
        if (access == null) {
            plugin.getLogger().warning("Worlds plugin not available, cannot create login world");
            return;
        }

        // 检查是否已注册
        try {
            Object registry = getWorldRegistryMethod.invoke(access);
            Object optional = registryGetMethod.invoke(registry, finalWorldKey);
            Method isPresentMethod = optional.getClass().getMethod("isPresent");
            boolean isPresent = (boolean) isPresentMethod.invoke(optional);

            if (isPresent) {
                Object future = loadMethod.invoke(access, finalWorldKey);
                Class<?> futureClass = future.getClass();
                Method thenAcceptMethod = futureClass.getMethod("thenAccept", java.util.function.Consumer.class);
                thenAcceptMethod.invoke(future, (java.util.function.Consumer<Object>) world -> {
                    SchedulerUtil.runTask(plugin, () -> {
                        this.loginWorld = (World) world;
                        this.ready = true;
                        applyWorldSettings((World) world);
                        try {
                            String ns = (String) keyNamespaceMethod.invoke(finalWorldKey);
                            String val = (String) keyValueMethod.invoke(finalWorldKey);
                            plugin.getLogger().info("Login world loaded: " + ns + ":" + val);
                        } catch (Exception ignored) {
                        }
                    });
                });
            } else {
                createLoginWorld(access, finalWorldKey);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load/create login world: " + e.getMessage());
        }
    }

    /**
     * 创建登录世界
     */
    private void createLoginWorld(Object access, Object worldKey) {
        if (!creating.compareAndSet(false, true)) {
            return; // 已在创建中
        }

        FileConfiguration config = plugin.getConfig();

        try {
            // 解析维度
            Object dimension = getDimension(config.getString("login-world.dimension", "OVERWORLD"));

            // 解析生成器类型
            Object generatorType = getGeneratorType(config.getString("login-world.generator-type", "FLAT"));

            // 解析种子
            Long seed = null;
            String seedStr = config.getString("login-world.seed", "");
            if (seedStr != null && !seedStr.isEmpty()) {
                try {
                    seed = Long.parseLong(seedStr);
                } catch (NumberFormatException ignored) {
                }
            }

            // 构建 Level
            Object builder = levelBuilderMethod.invoke(null, worldKey);
            builderDimensionMethod.invoke(builder, dimension);
            builderGeneratorTypeMethod.invoke(builder, generatorType);
            if (seed != null) {
                builderSeedMethod.invoke(builder, seed);
            }
            builderStructuresMethod.invoke(builder, Boolean.valueOf(config.getBoolean("login-world.structures", false)));
            builderBonusChestMethod.invoke(builder, Boolean.valueOf(config.getBoolean("login-world.bonus-chest", false)));
            builderHardcoreMethod.invoke(builder, Boolean.valueOf(config.getBoolean("login-world.hardcore", false)));

            Object level = builderBuildMethod.invoke(builder);

            try {
                String ns = (String) keyNamespaceMethod.invoke(worldKey);
                String val = (String) keyValueMethod.invoke(worldKey);
                plugin.getLogger().info("Creating login world: " + ns + ":" + val);
            } catch (Exception ignored) {
            }

            Object future = createMethod.invoke(access, level);
            Class<?> futureClass = future.getClass();
            Method thenAcceptMethod = futureClass.getMethod("thenAccept", java.util.function.Consumer.class);
            thenAcceptMethod.invoke(future, (java.util.function.Consumer<Object>) world -> {
                SchedulerUtil.runTask(plugin, () -> {
                    try {
                        Object registry = getWorldRegistryMethod.invoke(access);
                        registryRegisterMethod.invoke(registry, level, true);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to register world: " + e.getMessage());
                    }
                    this.loginWorld = (World) world;
                    this.ready = true;
                    applyWorldSettings((World) world);
                    try {
                        String ns = (String) keyNamespaceMethod.invoke(worldKey);
                        String val = (String) keyValueMethod.invoke(worldKey);
                        plugin.getLogger().info("Login world created: " + ns + ":" + val);
                    } catch (Exception ignored) {
                    }
                });
            });
        } catch (Exception e) {
            creating.set(false);
            plugin.getLogger().warning("Failed to create login world: " + e.getMessage());
        }
    }

    /**
     * 解析维度枚举
     */
    private Object getDimension(String dimStr) throws Exception {
        String upperDim = dimStr.toUpperCase();
        if ("THE_NETHER".equals(upperDim) || "NETHER".equals(upperDim)) {
            return dimensionClass.getField("THE_NETHER").get(null);
        } else if ("THE_END".equals(upperDim) || "END".equals(upperDim)) {
            return dimensionClass.getField("THE_END").get(null);
        } else {
            return dimensionClass.getField("OVERWORLD").get(null);
        }
    }

    /**
     * 解析生成器类型枚举
     */
    private Object getGeneratorType(String genStr) throws Exception {
        String upperGen = genStr.toUpperCase();
        if ("NORMAL".equals(upperGen)) {
            return generatorTypeClass.getField("NORMAL").get(null);
        } else if ("LARGE_BIOMES".equals(upperGen)) {
            return generatorTypeClass.getField("LARGE_BIOMES").get(null);
        } else if ("AMPLIFIED".equals(upperGen)) {
            return generatorTypeClass.getField("AMPLIFIED").get(null);
        } else if ("DEBUG".equals(upperGen)) {
            return generatorTypeClass.getField("DEBUG").get(null);
        } else if ("SINGLE_BIOME".equals(upperGen)) {
            return generatorTypeClass.getField("SINGLE_BIOME").get(null);
        } else {
            return generatorTypeClass.getField("FLAT").get(null);
        }
    }

    /**
     * 应用登录世界的特殊设置
     */
    public void applyWorldSettings(World world) {
        FileConfiguration config = plugin.getConfig();

        if (config.getBoolean("login-world.lock-daytime", true)) {
            world.setGameRuleValue("doDaylightCycle", "false");
            world.setTime(6000); // 中午
        }

        if (config.getBoolean("login-world.disable-weather", true)) {
            world.setGameRuleValue("doWeatherCycle", "false");
            world.setStorm(false);
            world.setThundering(false);
        }

        if (config.getBoolean("login-world.disable-mob-spawning", true)) {
            world.setGameRuleValue("doMobSpawning", "false");
        }

        if (config.getBoolean("login-world.disable-pvp", true)) {
            world.setPVP(false);
        }

        // 性能模式：禁用生物生成、时间流逝、天气更替
        if (config.getBoolean("login-world.performance-mode", true)) {
            world.setGameRuleValue("doDaylightCycle", "false");
            world.setGameRuleValue("doWeatherCycle", "false");
            world.setGameRuleValue("doMobSpawning", "false");
            world.setGameRuleValue("randomTickSpeed", "0");
            world.setGameRuleValue("doFireTick", "false");
            world.setGameRuleValue("doImmediateRespawn", "true");
        }
    }

    /**
     * 获取登录世界的出生点位置
     */
    public Location getLoginSpawnLocation() {
        if (loginWorld == null) {
            return null;
        }
        FileConfiguration config = plugin.getConfig();
        double x = config.getDouble("login-world.spawn.x", 0.0);
        double y = config.getDouble("login-world.spawn.y", 64.0);
        double z = config.getDouble("login-world.spawn.z", 0.0);
        float pitch = (float) config.getDouble("login-world.spawn.pitch", 0.0);
        float yaw = (float) config.getDouble("login-world.spawn.yaw", 0.0);
        return new Location(loginWorld, x, y, z, yaw, pitch);
    }

    /**
     * 保存玩家在主世界的退出位置和游戏模式
     */
    public void savePlayerQuitLocation(org.bukkit.entity.Player player) {
        String mainWorldName = plugin.getConfig().getString("queue.spawn.world", "world");
        World mainWorld = plugin.getServer().getWorld(mainWorldName);
        if (mainWorld == null) {
            mainWorld = plugin.getServer().getWorlds().get(0);
        }
        // 如果玩家当前在登录世界，保留之前保存的数据（不要覆盖）
        if (loginWorld != null && player.getWorld().equals(loginWorld)) {
            if (plugin.isDebug()) {
                plugin.getLogger().info("Player " + player.getName() + " quit in login world, preserving existing saved data.");
            }
            return;
        }
        // 保存玩家在当前世界的退出位置和游戏模式
        if (mainWorld != null) {
            plugin.getAuthManager().savePlayerLocation(player.getUniqueId(), player.getLocation().clone(), player.getGameMode());
            if (plugin.isDebug()) {
                plugin.getLogger().info("Saved quit location and gamemode " + player.getGameMode() + " for player " + player.getName() + ": " + player.getLocation().getBlockX() + "," + player.getLocation().getBlockY() + "," + player.getLocation().getBlockZ());
            }
        }
    }

    /**
     * 获取玩家在主世界的位置（优先使用玩家上次退出位置）
     */
    public Location getMainWorldLocation(org.bukkit.entity.Player player) {
        String worldName = plugin.getConfig().getString("queue.spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            world = plugin.getServer().getWorlds().get(0);
        }
        if (world == null) {
            return null;
        }

        // 如果玩家已经在主世界，使用当前位置
        if (player.getWorld().equals(world)) {
            return player.getLocation();
        }

        // 优先使用数据库保存的上次退出位置
        Location quitLocation = plugin.getAuthManager().getPlayerLocation(player.getUniqueId());
        if (quitLocation != null && quitLocation.getWorld() != null) {
            if (plugin.isDebug()) {
                plugin.getLogger().info("Restored quit location for player " + player.getName() + ": " + quitLocation.getBlockX() + "," + quitLocation.getBlockY() + "," + quitLocation.getBlockZ());
            }
            return quitLocation;
        }

        // 使用玩家 bed spawn location（如果设置了）
        // 注意：在 Folia 上，玩家当前区域与目标世界可能不同，
        // 跨世界调用 getBedSpawnLocation 会导致 World mismatch 异常。
        // 因此使用 try-catch 包裹，异常时回退到世界默认出生点。
        Location bedSpawn = null;
        try {
            bedSpawn = player.getBedSpawnLocation();
        } catch (IllegalStateException e) {
            if (plugin.isDebug()) {
                plugin.getLogger().info("Skipping bed spawn check for " + player.getName() + " due to world region mismatch");
            }
        }
        if (bedSpawn != null) {
            return bedSpawn;
        }

        // 使用世界默认出生点，并在配置半径内随机偏移
        Location spawn = world.getSpawnLocation();
        double radius = plugin.getConfig().getDouble("queue.spawn.radius", 5.0);
        if (radius > 0) {
            double angle = Math.random() * 2 * Math.PI;
            double distance = Math.random() * radius;
            double x = spawn.getX() + distance * Math.cos(angle);
            double z = spawn.getZ() + distance * Math.sin(angle);
            return new Location(world, x, spawn.getY(), z, spawn.getYaw(), spawn.getPitch());
        }
        return spawn;
    }

    /**
     * 将玩家传送到登录世界
     */
    public void teleportToLoginWorld(org.bukkit.entity.Player player) {
        Location spawn = getLoginSpawnLocation();
        if (spawn != null) {
            SchedulerUtil.teleport(player, spawn);
        }
    }

    /**
     * 将玩家传送到主世界
     */
    public void teleportToMainWorld(org.bukkit.entity.Player player) {
        Location target = getMainWorldLocation(player);
        if (target != null) {
            SchedulerUtil.teleport(player, target, () -> {
                // 恢复玩家上次退出时的游戏模式
                org.bukkit.GameMode savedGameMode = plugin.getAuthManager().getPlayerGameMode(player.getUniqueId());
                if (savedGameMode != null) {
                    player.setGameMode(savedGameMode);
                    if (plugin.isDebug()) {
                        plugin.getLogger().info("Restored gamemode " + savedGameMode + " for player " + player.getName());
                    }
                }
                // 删除数据库记录（位置和游戏模式都已恢复）
                plugin.getAuthManager().deletePlayerLocation(player.getUniqueId());
                plugin.getLogger().info("Player " + player.getName() + " teleported to main world: " + target.getWorld().getName() + " at " + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());
            });
        } else {
            plugin.getLogger().warning("Failed to get main world location for player " + player.getName());
        }
    }

    /**
     * 检查当前是否为登录世界模式
     */
    public boolean isWorldMode() {
        return "WORLD".equalsIgnoreCase(plugin.getConfig().getString("work-mode", "PROXY"));
    }

    /**
     * 检查登录世界是否已就绪
     */
    public boolean isReady() {
        return ready && loginWorld != null;
    }

    /**
     * 获取登录世界
     */
    public World getLoginWorld() {
        return loginWorld;
    }

    /**
     * 检查玩家是否在登录世界中
     */
    public boolean isInLoginWorld(org.bukkit.entity.Player player) {
        return loginWorld != null && loginWorld.equals(player.getWorld());
    }

    /**
     * 获取 WorldsAccess 实例（反射）
     */
    private Object getWorldsAccess() {
        if (!reflectionReady) {
            return null;
        }
        try {
            return worldsAccessMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 监听世界加载事件，确保登录世界设置被应用
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!isWorldMode() || !reflectionReady) {
            return;
        }
        World world = event.getWorld();
        FileConfiguration config = plugin.getConfig();
        String keyString = config.getString("login-world.key", "loginqueue2:lobby");
        try {
            Object worldKey = keyMethod.invoke(null, keyString);
            if (world.key().equals(worldKey)) {
                this.loginWorld = world;
                this.ready = true;
                applyWorldSettings(world);
            }
        } catch (Exception ignored) {
        }
    }
}
