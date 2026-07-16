package top.mcocet.loginqueue2.listener;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.auth.AuthManager;
import top.mcocet.loginqueue2.auth.AuthRestrictionListener;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.queue.PriorityManager;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.*;

public class PlayerJoinListener implements Listener {

    private final LoginQueue2 plugin;
    private final BungeeMessenger messenger;
    private final AuthManager authManager;
    private final AuthRestrictionListener authRestrictionListener;
    private final double threshold;
    private final LanguageManager languageManager;
    private final PriorityManager priorityManager;

    // 等待队列：按优先级排序，同优先级按入队时间先后（FIFO）
    private final PriorityQueue<QueueEntry> waitingQueue;
    // 已允许进入的玩家
    private final Set<UUID> allowedPlayers = new HashSet<>();
    // 队列手动暂停状态
    private boolean queuePaused = false;

    public PlayerJoinListener(LoginQueue2 plugin, BungeeMessenger messenger,
                              AuthManager authManager, AuthRestrictionListener authRestrictionListener) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.authManager = authManager;
        this.authRestrictionListener = authRestrictionListener;
        FileConfiguration config = plugin.getConfig();
        this.threshold = Math.max(0.0, Math.min(1.0, config.getDouble("queue.threshold", 0.8)));
        this.languageManager = plugin.getLanguageManager();
        this.priorityManager = new PriorityManager(plugin);

        // 优先级数字越大越靠前，同优先级按时间戳升序（先来的在前）
        this.waitingQueue = new PriorityQueue<>(
                Comparator.comparingInt(QueueEntry::getPriority).reversed()
                        .thenComparingLong(QueueEntry::getTimestamp)
        );
    }

    /**
     * 重新加载优先级配置
     */
    public void reloadPriority() {
        priorityManager.reload();
    }

    /**
     * 手动暂停队列处理
     */
    public void pauseQueue() {
        queuePaused = true;
        notifyWaitingPlayers(languageManager.getMessage("queue-paused-manual"));
    }

    /**
     * 手动恢复队列处理
     */
    public void resumeQueue() {
        queuePaused = false;
        notifyWaitingPlayers(languageManager.getMessage("queue-resumed"));
        processQueue();
    }

    /**
     * 检查队列是否被手动暂停
     */
    public boolean isQueuePaused() {
        return queuePaused;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();

        // 设置玩家游戏模式
        applyGameMode(player);

        // 设置玩家出生点（延迟1 tick执行，避免Folia上teleportAsync冲突）
        if (worldMode && loginWorldManager != null) {
            // WORLD 模式：传送到登录世界
            SchedulerUtil.runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    loginWorldManager.teleportToLoginWorld(player);
                }
            }, 1L);
        } else {
            // PROXY 模式：传送到配置出生点
            teleportToSpawn(player);
        }

        // 显示服务器状态计分板
        if (plugin.getScoreboardManager() != null && plugin.getScoreboardManager().isEnabled()) {
            plugin.getScoreboardManager().showScoreboard(player);
        }

        // 如果已经允许进入，直接放行
        if (allowedPlayers.contains(uuid)) {
            return;
        }

        // 认证功能启用时，未认证玩家不自动入队
        if (authManager.isEnabled()) {
            authRestrictionListener.removeAuthenticated(uuid);
            if (!authManager.isRegistered(player.getName())) {
                player.sendMessage(languageManager.getMessage("auth-welcome-register"));
            } else {
                player.sendMessage(languageManager.getMessage("auth-welcome-login"));
            }
            return;
        }

        boolean autoQueue = plugin.getConfig().getBoolean("queue.auto-queue", true);
        if (!autoQueue) {
            player.sendMessage(languageManager.getMessage("manual-queue-hint"));
            return;
        }

        // 延迟执行，等待 BungeeCord 数据刷新和锁定时间
        long lockTime = plugin.getConfig().getLong("queue.lock-time", 3);
        SchedulerUtil.runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            // WORLD 模式下不需要检查 BungeeCord 主服务器
            if (worldMode) {
                // 已在队列中则不再重复添加
                if (isInQueue(uuid)) {
                    return;
                }

                // 计算玩家优先级并入队
                int priority = priorityManager.calculatePriority(player);
                waitingQueue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));

                // 通知玩家排队位置
                sendQueueStatus(player, uuid);

                // 尝试放行队列中的玩家
                processQueue();
                return;
            }

            // 先判断主服务器是否在线（缓存中有数据时直接判断）
            if (messenger.isMainServerOnline()) {
                // 已在队列中则不再重复添加
                if (isInQueue(uuid)) {
                    return;
                }

                // 计算玩家优先级并入队
                int priority = priorityManager.calculatePriority(player);
                waitingQueue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));

                // 通知玩家排队位置
                sendQueueStatus(player, uuid);

                // 尝试放行队列中的玩家
                processQueue();
                return;
            }

            // 缓存中没有有效数据，进行实时检测（BC 优先模式下首次连接时缓存可能为空）
            player.sendMessage(languageManager.getMessage("checking-main-server"));
            messenger.checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
                SchedulerUtil.runTask(plugin, () -> {
                    if (throwable != null || !online) {
                        player.sendMessage(languageManager.getMessage("main-offline"));
                        return;
                    }

                    // 玩家已离线则忽略
                    if (!player.isOnline()) {
                        return;
                    }

                    // 已在队列中则不再重复添加
                    if (isInQueue(uuid)) {
                        return;
                    }

                    // 计算玩家优先级并入队
                    int priority = priorityManager.calculatePriority(player);
                    waitingQueue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));

                    // 通知玩家排队位置
                    sendQueueStatus(player, uuid);

                    // 尝试放行队列中的玩家
                    processQueue();
                });
            });
        }, lockTime * 20L);
    }

    private void sendQueueStatus(Player player, UUID uuid) {
        int position = getPosition(uuid);
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();

        int online;
        int max;

        if (worldMode) {
            // WORLD 模式：显示主世界在线人数
            String mainWorldName = plugin.getConfig().getString("queue.spawn.world", "world");
            World mainWorld = plugin.getServer().getWorld(mainWorldName);
            if (mainWorld == null) {
                mainWorld = plugin.getServer().getWorlds().get(0);
            }
            online = mainWorld != null ? mainWorld.getPlayers().size() : 0;
            max = plugin.getConfig().getInt("queue.max-online", 50);
        } else {
            // PROXY 模式：显示代理端主服务器信息
            online = messenger.getMainServerPlayerCount();
            max = messenger.getMainServerMaxPlayers();
            // 多服务器模式下显示总在线人数
            int totalOnline = 0;
            int totalMax = 0;
            for (BungeeMessenger.ServerStatus status : messenger.getAllServerStatus().values()) {
                if (status.isOnline()) {
                    totalOnline += status.getOnlinePlayers();
                    totalMax += status.getMaxPlayers();
                }
            }
            // 如果有多个服务器在线，显示总数
            if (totalMax > 0 && messenger.getAllServerStatus().size() > 1) {
                online = totalOnline;
                max = totalMax;
            }
        }

        player.sendMessage(languageManager.getMessage("waiting",
                "position", String.valueOf(position),
                "online", String.valueOf(online),
                "max", String.valueOf(max)));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // 隐藏计分板
        if (plugin.getScoreboardManager() != null) {
            plugin.getScoreboardManager().hideScoreboard(player);
        }
        // WORLD 模式下保存玩家在主世界的退出位置
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            loginWorldManager.savePlayerQuitLocation(player);
        }
        allowedPlayers.remove(uuid);
        waitingQueue.removeIf(entry -> entry.getUuid().equals(uuid));
        if (authRestrictionListener != null) {
            authRestrictionListener.removeAuthenticated(uuid);
        }
        processQueue();
    }

    private int getPosition(UUID uuid) {
        int position = 1;
        for (QueueEntry entry : waitingQueue) {
            if (entry.getUuid().equals(uuid)) {
                return position;
            }
            position++;
        }
        return 0;
    }

    public boolean isInQueue(UUID uuid) {
        for (QueueEntry entry : waitingQueue) {
            if (entry.getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public void addPlayerToQueue(Player player) {
        if (isInQueue(player.getUniqueId())) {
            return;
        }

        // 认证模式下，必须先通过登录认证才能入队
        if (authManager.isEnabled() && authRestrictionListener != null) {
            if (!authRestrictionListener.isAuthenticated(player.getUniqueId())) {
                player.sendMessage(languageManager.getMessage("auth-please-login-first"));
                return;
            }
        }

        // 通知代理端玩家登录成功（允许使用 /server 命令）
        // WORLD 模式下不需要通知代理端
        if (authManager.isEnabled()) {
            LoginWorldManager lwm = plugin.getLoginWorldManager();
            boolean worldMode = lwm != null && lwm.isWorldMode();
            if (!worldMode) {
                messenger.notifyLoginSuccess(player);
            }
        }

        int priority = priorityManager.calculatePriority(player);
        waitingQueue.offer(new QueueEntry(player.getUniqueId(), priority, System.currentTimeMillis()));
        sendQueueStatus(player, player.getUniqueId());
        processQueue();
    }

    public void allowPlayerDirectly(Player player) {
        if (isInQueue(player.getUniqueId())) {
            waitingQueue.removeIf(entry -> entry.getUuid().equals(player.getUniqueId()));
        }
        allowedPlayers.add(player.getUniqueId());
        player.sendMessage(languageManager.getMessage("entering"));

        // 清除队列物品（加入游戏按钮）
        if (plugin.getQueueItemListener() != null) {
            plugin.getQueueItemListener().removeAllQueueItems(player);
        }

        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();

        if (worldMode && loginWorldManager != null) {
            // WORLD 模式：传送到主世界
            loginWorldManager.teleportToMainWorld(player);
        } else {
            // PROXY 模式：通过代理端跳转
            messenger.connectToOptimalServer(player);
        }
    }

    public List<String> getQueuePlayerNames() {
        List<String> names = new ArrayList<>();
        for (QueueEntry entry : waitingQueue) {
            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player != null) {
                names.add(player.getName());
            }
        }
        return names;
    }

    public int getQueueSize() {
        return waitingQueue.size();
    }

    public Set<UUID> getAllowedPlayers() {
        return allowedPlayers;
    }

    /**
     * 将指定玩家向前移动一位排队名次（与前一名玩家交换位置）
     *
     * @param player 要前进的玩家
     * @return 是否成功前进（玩家不在队列中或已在第一位时返回false）
     */
    public boolean promotePlayerInQueue(Player player) {
        UUID targetUuid = player.getUniqueId();
        if (!isInQueue(targetUuid)) {
            return false;
        }

        List<QueueEntry> entries = new ArrayList<>();
        while (!waitingQueue.isEmpty()) {
            entries.add(waitingQueue.poll());
        }

        int targetIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getUuid().equals(targetUuid)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex <= 0) {
            for (QueueEntry entry : entries) {
                waitingQueue.offer(entry);
            }
            return false;
        }

        // 与前一名玩家交换位置
        QueueEntry temp = entries.get(targetIndex);
        entries.set(targetIndex, entries.get(targetIndex - 1));
        entries.set(targetIndex - 1, temp);

        for (QueueEntry entry : entries) {
            waitingQueue.offer(entry);
        }

        return true;
    }

    private void applyGameMode(Player player) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("queue.set-gamemode", true)) {
            return;
        }

        String modeName = config.getString("queue.gamemode", "ADVENTURE");
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(modeName.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            plugin.getLogger().warning(languageManager.getLogMessage("invalid-gamemode", "mode", modeName));
            gameMode = GameMode.ADVENTURE;
        }

        player.setGameMode(gameMode);
    }

    private void teleportToSpawn(Player player) {
        FileConfiguration config = plugin.getConfig();
        String worldName = config.getString("queue.spawn.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            world = player.getWorld();
        }

        double centerX = config.getDouble("queue.spawn.x", 0.0);
        double centerY = config.getDouble("queue.spawn.y", 64.0);
        double centerZ = config.getDouble("queue.spawn.z", 0.0);
        float pitch = (float) config.getDouble("queue.spawn.pitch", 0.0);
        float yaw = (float) config.getDouble("queue.spawn.yaw", 0.0);
        double radius = config.getDouble("queue.spawn.radius", 5.0);

        double x = centerX;
        double y = centerY;
        double z = centerZ;

        if (radius > 0) {
            double angle = Math.random() * 2 * Math.PI;
            double distance = Math.random() * radius;
            x = centerX + distance * Math.cos(angle);
            z = centerZ + distance * Math.sin(angle);
        }

        Location spawnLocation = new Location(world, x, y, z, yaw, pitch);
        SchedulerUtil.teleport(player, spawnLocation);
    }

    private void processQueue() {
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();

        int totalMax;
        int totalOnline;
        boolean anyOnline;

        if (worldMode) {
            // WORLD 模式：计算主世界在线人数
            String mainWorldName = plugin.getConfig().getString("queue.spawn.world", "world");
            World mainWorld = plugin.getServer().getWorld(mainWorldName);
            if (mainWorld == null) {
                mainWorld = plugin.getServer().getWorlds().get(0);
            }
            totalOnline = mainWorld != null ? mainWorld.getPlayers().size() : 0;
            totalMax = plugin.getConfig().getInt("queue.max-online", 50);
            anyOnline = mainWorld != null;
        } else {
            // PROXY 模式：计算所有在线主服务器的总人数和总容量
            totalMax = 0;
            totalOnline = 0;
            anyOnline = false;
            for (BungeeMessenger.ServerStatus status : messenger.getAllServerStatus().values()) {
                if (status.isOnline()) {
                    anyOnline = true;
                    int max = status.getMaxPlayers();
                    int online = status.getOnlinePlayers();
                    if (max <= 0) {
                        max = plugin.getConfig().getInt("queue.max-online", 50);
                    }
                    totalMax += max;
                    totalOnline += online;
                }
            }
        }

        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("queue-processing", "anyOnline", String.valueOf(anyOnline), "totalOnline", String.valueOf(totalOnline), "totalMax", String.valueOf(totalMax), "queueSize", String.valueOf(waitingQueue.size())));
        }

        // 手动暂停队列
        if (queuePaused) {
            if (plugin.isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("queue-paused-manual-log"));
            }
            return;
        }

        if (!anyOnline) {
            if (plugin.isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("queue-all-offline"));
            }
            return;
        }

        // 超过阈值时暂停放行
        if (totalMax > 0 && (double) totalOnline / totalMax >= threshold) {
            notifyWaitingPlayers(languageManager.getMessage("threshold-reached"));
            return;
        }

        int availableSlots = Math.max(0, totalMax - totalOnline);
        if (plugin.isDebug()) {
            plugin.getLogger().info(languageManager.getLogMessage("queue-available-slots", "slots", String.valueOf(availableSlots)));
        }

        while (availableSlots > 0 && !waitingQueue.isEmpty()) {
            QueueEntry entry = waitingQueue.poll();
            if (entry == null) break;

            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }

            allowedPlayers.add(entry.getUuid());
            player.sendMessage(languageManager.getMessage("entering"));
            if (plugin.isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("queue-player-allowed", "player", player.getName()));
            }

            // 清除队列物品（加入游戏按钮）
            if (plugin.getQueueItemListener() != null) {
                plugin.getQueueItemListener().removeAllQueueItems(player);
            }

            if (worldMode && loginWorldManager != null) {
                // WORLD 模式：传送到主世界
                loginWorldManager.teleportToMainWorld(player);
            } else {
                // PROXY 模式：通过代理端跳转
                messenger.connectToOptimalServer(player);
            }
            availableSlots--;
        }
    }

    private void notifyWaitingPlayers(String message) {
        for (QueueEntry entry : waitingQueue) {
            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private static class QueueEntry {
        private final UUID uuid;
        private final int priority;
        private final long timestamp;

        QueueEntry(UUID uuid, int priority, long timestamp) {
            this.uuid = uuid;
            this.priority = priority;
            this.timestamp = timestamp;
        }

        UUID getUuid() {
            return uuid;
        }

        int getPriority() {
            return priority;
        }

        long getTimestamp() {
            return timestamp;
        }
    }
}
