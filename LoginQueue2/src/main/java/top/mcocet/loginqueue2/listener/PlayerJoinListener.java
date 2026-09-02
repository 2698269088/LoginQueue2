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
import top.mcocet.loginqueue2.auth.AuthMeCompatManager;
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
    private final AuthMeCompatManager authMeCompatManager;
    private final double threshold;
    private final LanguageManager languageManager;
    private final PriorityManager priorityManager;

    // 等待队列：按优先级排序，同优先级按入队时间先后（FIFO）
    private final PriorityQueue<QueueEntry> waitingQueue;
    // 多服务器独立队列：仅在多服务器跳转模式启用时使用
    private final Map<String, PriorityQueue<QueueEntry>> serverQueues = new HashMap<>();
    // 记录玩家所属目标服务器
    private final Map<UUID, String> playerTargetServerMap = new HashMap<>();
    // 已允许进入的玩家
    private final Set<UUID> allowedPlayers = new HashSet<>();
    // 队列手动暂停状态
    private boolean queuePaused = false;
    // 虚拟队列处理器（当虚拟玩家被放行时回调）
    private VirtualQueueHandler virtualQueueHandler;

    public PlayerJoinListener(LoginQueue2 plugin, BungeeMessenger messenger,
                              AuthManager authManager, AuthRestrictionListener authRestrictionListener,
                              AuthMeCompatManager authMeCompatManager) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.authManager = authManager;
        this.authRestrictionListener = authRestrictionListener;
        this.authMeCompatManager = authMeCompatManager;
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
     * 判断当前是否启用每服独立队列模式
     */
    public boolean isPerServerQueueMode() {
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();
        return !worldMode
                && plugin.getConfig().getBoolean("queue.per-server-queue", false)
                && plugin.getConfig().getBoolean("udp-sync.enabled", false)
                && plugin.getConfig().getBoolean("udp-sync.server-selector.enabled", false);
    }

    /**
     * 获取指定服务器的队列
     */
    private PriorityQueue<QueueEntry> getServerQueue(String serverName) {
        return serverQueues.computeIfAbsent(serverName, k -> new PriorityQueue<>(
                Comparator.comparingInt(QueueEntry::getPriority).reversed()
                        .thenComparingLong(QueueEntry::getTimestamp)
        ));
    }

    /**
     * 获取玩家当前所在的队列
     */
    private PriorityQueue<QueueEntry> getPlayerQueue(UUID uuid) {
        if (isPerServerQueueMode()) {
            String targetServer = playerTargetServerMap.get(uuid);
            if (targetServer != null) {
                return getServerQueue(targetServer);
            }
            return null;
        }
        return waitingQueue;
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

        // WORLD 模式下不要在主世界提前切换游戏模式，
        // 等玩家真正进入登录世界后再应用登录世界配置。
        if (!worldMode) {
            applyGameMode(player);
        }

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

        // 根据优先级判断使用哪种认证系统
        String activeAuth = plugin.getActiveAuthSystem();

        // 认证功能启用时，未认证玩家不自动入队
        if ("BUILTIN".equals(activeAuth)) {
            authRestrictionListener.removeAuthenticated(uuid);
            if (!authManager.isRegistered(player.getName())) {
                player.sendMessage(languageManager.getMessage("auth-welcome-register"));
            } else {
                player.sendMessage(languageManager.getMessage("auth-welcome-login"));
            }
            return;
        }

        // AuthMe 兼容模式：检查玩家是否已通过 AuthMe 登录
        if ("AUTHME".equals(activeAuth)) {
            if (!authMeCompatManager.isAuthenticated(player)) {
                player.sendMessage(languageManager.getMessage("authme-please-login-first"));
                return;
            }
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
        } else if (isPerServerQueueMode()) {
            // 多服独立队列模式：显示目标服务器状态
            String targetServer = playerTargetServerMap.get(uuid);
            if (targetServer != null) {
                BungeeMessenger.ServerStatus status = messenger.getServerStatus(targetServer);
                if (status != null && status.isOnline()) {
                    online = status.getOnlinePlayers();
                    max = status.getMaxPlayers();
                } else {
                    online = 0;
                    max = plugin.getConfig().getInt("queue.max-online", 50);
                }
            } else {
                online = 0;
                max = plugin.getConfig().getInt("queue.max-online", 50);
            }
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
        if (isPerServerQueueMode()) {
            String targetServer = playerTargetServerMap.remove(uuid);
            if (targetServer != null) {
                PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
                if (queue != null) {
                    queue.removeIf(entry -> entry.getUuid().equals(uuid));
                }
            }
        } else {
            waitingQueue.removeIf(entry -> entry.getUuid().equals(uuid));
        }
        if (authRestrictionListener != null) {
            authRestrictionListener.removeAuthenticated(uuid);
        }
        processQueue();
    }

    private int getPosition(UUID uuid) {
        PriorityQueue<QueueEntry> queue = getPlayerQueue(uuid);
        if (queue == null) {
            return 0;
        }
        int position = 1;
        for (QueueEntry entry : queue) {
            if (entry.getUuid().equals(uuid)) {
                return position;
            }
            position++;
        }
        return 0;
    }

    public boolean isInQueue(UUID uuid) {
        PriorityQueue<QueueEntry> queue = getPlayerQueue(uuid);
        if (queue == null) {
            return false;
        }
        for (QueueEntry entry : queue) {
            if (entry.getUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public void addPlayerToQueue(Player player) {
        addPlayerToQueue(player, null);
    }

    public void addPlayerToQueue(Player player, String targetServer) {
        if (isInQueue(player.getUniqueId())) {
            return;
        }

        // 根据优先级判断使用哪种认证系统
        String activeAuth = plugin.getActiveAuthSystem();

        // 认证模式下，必须先通过登录认证才能入队
        if ("BUILTIN".equals(activeAuth) && authRestrictionListener != null) {
            if (!authRestrictionListener.isAuthenticated(player.getUniqueId())) {
                player.sendMessage(languageManager.getMessage("auth-please-login-first"));
                return;
            }
        }

        // AuthMe 兼容模式：检查玩家是否已通过 AuthMe 登录
        if ("AUTHME".equals(activeAuth)) {
            if (!authMeCompatManager.isAuthenticated(player)) {
                player.sendMessage(languageManager.getMessage("authme-please-login-first"));
                return;
            }
        }

        // 通知代理端玩家登录成功（允许使用 /server 命令）
        // WORLD 模式下不需要通知代理端
        if ("BUILTIN".equals(activeAuth)) {
            LoginWorldManager lwm = plugin.getLoginWorldManager();
            boolean worldMode = lwm != null && lwm.isWorldMode();
            if (!worldMode) {
                messenger.notifyLoginSuccess(player);
            }
        }

        int priority = priorityManager.calculatePriority(player);
        if (isPerServerQueueMode() && targetServer != null) {
            playerTargetServerMap.put(player.getUniqueId(), targetServer);
            getServerQueue(targetServer).offer(new QueueEntry(player.getUniqueId(), priority, System.currentTimeMillis()));
        } else {
            waitingQueue.offer(new QueueEntry(player.getUniqueId(), priority, System.currentTimeMillis()));
        }
        sendQueueStatus(player, player.getUniqueId());
        processQueue();
    }

    public void setVirtualQueueHandler(VirtualQueueHandler handler) {
        this.virtualQueueHandler = handler;
    }

    /**
     * 添加虚拟玩家到队列（玩家位于子服务器，通过 UDP 请求入队）
     *
     * @param uuid         玩家 UUID
     * @param targetServer 目标服务器
     * @param sourceServer 来源子服务器名称
     * @param priority     优先级
     * @return 是否成功入队
     */
    public boolean addVirtualPlayerToQueue(UUID uuid, String targetServer, String sourceServer, int priority) {
        if (!isPerServerQueueMode()) {
            return false;
        }
        if (isInQueue(uuid)) {
            return false;
        }
        if (targetServer == null || targetServer.isEmpty()) {
            return false;
        }
        playerTargetServerMap.put(uuid, targetServer);
        getServerQueue(targetServer).offer(new QueueEntry(uuid, priority, System.currentTimeMillis(), sourceServer));
        processQueue();
        return true;
    }

    /**
     * 将指定 UUID 的虚拟玩家移出队列
     */
    public boolean removeVirtualPlayerFromQueue(UUID uuid) {
        if (!isInQueue(uuid)) {
            return false;
        }
        String targetServer = playerTargetServerMap.remove(uuid);
        if (targetServer != null) {
            PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
            if (queue != null) {
                queue.removeIf(entry -> entry.getUuid().equals(uuid));
            }
        } else {
            waitingQueue.removeIf(entry -> entry.getUuid().equals(uuid));
        }
        processQueue();
        return true;
    }

    /**
     * 获取所有虚拟玩家的 UUID 列表
     */
    public Set<UUID> getVirtualPlayerUuids() {
        Set<UUID> virtualUuids = new HashSet<>();
        if (isPerServerQueueMode()) {
            for (PriorityQueue<QueueEntry> queue : serverQueues.values()) {
                for (QueueEntry entry : queue) {
                    if (entry.isVirtual()) {
                        virtualUuids.add(entry.getUuid());
                    }
                }
            }
        } else {
            for (QueueEntry entry : waitingQueue) {
                if (entry.isVirtual()) {
                    virtualUuids.add(entry.getUuid());
                }
            }
        }
        return virtualUuids;
    }

    /**
     * 获取虚拟玩家在队列中的位置
     */
    public int getVirtualPlayerPosition(UUID uuid) {
        return getPosition(uuid);
    }

    /**
     * 获取虚拟玩家的来源子服务器名称
     */
    public String getVirtualPlayerSourceServer(UUID uuid) {
        PriorityQueue<QueueEntry> queue = getPlayerQueue(uuid);
        if (queue == null) {
            return null;
        }
        for (QueueEntry entry : queue) {
            if (entry.getUuid().equals(uuid) && entry.isVirtual()) {
                return entry.getSourceServer();
            }
        }
        return null;
    }

    /**
     * 获取虚拟玩家的目标服务器名称
     */
    public String getVirtualPlayerTargetServer(UUID uuid) {
        return playerTargetServerMap.get(uuid);
    }

    public void allowPlayerDirectly(Player player) {
        if (isInQueue(player.getUniqueId())) {
            if (isPerServerQueueMode()) {
                String targetServer = playerTargetServerMap.remove(player.getUniqueId());
                if (targetServer != null) {
                    PriorityQueue<QueueEntry> queue = serverQueues.get(targetServer);
                    if (queue != null) {
                        queue.removeIf(entry -> entry.getUuid().equals(player.getUniqueId()));
                    }
                }
            } else {
                waitingQueue.removeIf(entry -> entry.getUuid().equals(player.getUniqueId()));
            }
        }
        allowedPlayers.add(player.getUniqueId());
        player.sendMessage(languageManager.getMessage("entering"));

        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        boolean worldMode = loginWorldManager != null && loginWorldManager.isWorldMode();

        // 非 WORLD 模式下清除队列物品（WORLD 模式由 WorldInventoryListener 处理）
        if (!worldMode && plugin.getQueueItemListener() != null) {
            plugin.getQueueItemListener().removeAllQueueItems(player);
        }

        if (worldMode && loginWorldManager != null) {
            // WORLD 模式：传送到主世界
            // 背包恢复由 WorldInventoryListener 在 PlayerChangedWorldEvent 中处理
            loginWorldManager.teleportToMainWorld(player);
        } else if (isPerServerQueueMode()) {
            // 多服独立队列模式：跳转到目标服务器
            String targetServer = playerTargetServerMap.get(player.getUniqueId());
            if (targetServer != null) {
                messenger.connectPlayerToServer(player, targetServer);
            } else {
                messenger.connectToOptimalServer(player);
            }
        } else {
            // PROXY 模式：通过代理端跳转
            messenger.connectToOptimalServer(player);
        }
    }

    public List<String> getQueuePlayerNames() {
        List<String> names = new ArrayList<>();
        if (isPerServerQueueMode()) {
            for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
                for (QueueEntry queueEntry : entry.getValue()) {
                    Player player = plugin.getServer().getPlayer(queueEntry.getUuid());
                    if (player != null) {
                        names.add(player.getName() + " [" + entry.getKey() + "]");
                    }
                }
            }
        } else {
            for (QueueEntry entry : waitingQueue) {
                Player player = plugin.getServer().getPlayer(entry.getUuid());
                if (player != null) {
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    public int getQueueSize() {
        if (isPerServerQueueMode()) {
            int total = 0;
            for (PriorityQueue<QueueEntry> queue : serverQueues.values()) {
                total += queue.size();
            }
            return total;
        }
        return waitingQueue.size();
    }

    public Map<String, Integer> getServerQueueSizes() {
        Map<String, Integer> sizes = new HashMap<>();
        for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
            sizes.put(entry.getKey(), entry.getValue().size());
        }
        return sizes;
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
        PriorityQueue<QueueEntry> queue = getPlayerQueue(targetUuid);
        if (queue == null || !isInQueue(targetUuid)) {
            return false;
        }

        List<QueueEntry> entries = new ArrayList<>();
        while (!queue.isEmpty()) {
            entries.add(queue.poll());
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
                queue.offer(entry);
            }
            return false;
        }

        // 与前一名玩家交换位置
        QueueEntry temp = entries.get(targetIndex);
        entries.set(targetIndex, entries.get(targetIndex - 1));
        entries.set(targetIndex - 1, temp);

        for (QueueEntry entry : entries) {
            queue.offer(entry);
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

        if (isPerServerQueueMode()) {
            processPerServerQueues();
            return;
        }

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

            // 非 WORLD 模式下清除队列物品（WORLD 模式由 WorldInventoryListener 处理）
            if (!worldMode && plugin.getQueueItemListener() != null) {
                plugin.getQueueItemListener().removeAllQueueItems(player);
            }

            if (worldMode && loginWorldManager != null) {
                // WORLD 模式：传送到主世界
                // 背包恢复由 WorldInventoryListener 在 PlayerChangedWorldEvent 中处理
                loginWorldManager.teleportToMainWorld(player);
            } else {
                // PROXY 模式：通过代理端跳转
                messenger.connectToOptimalServer(player);
            }
            availableSlots--;
        }
    }

    private void processPerServerQueues() {
        if (queuePaused) {
            if (plugin.isDebug()) {
                plugin.getLogger().info(languageManager.getLogMessage("queue-paused-manual-log"));
            }
            return;
        }

        for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
            String serverName = entry.getKey();
            PriorityQueue<QueueEntry> queue = entry.getValue();

            BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
            if (status == null || !status.isOnline()) {
                continue;
            }

            int max = status.getMaxPlayers();
            int online = status.getOnlinePlayers();
            if (max <= 0) {
                max = plugin.getConfig().getInt("queue.max-online", 50);
            }

            if ((double) online / max >= threshold) {
                notifyServerQueuePlayers(serverName, languageManager.getMessage("threshold-reached"));
                continue;
            }

            int availableSlots = Math.max(0, max - online);
            while (availableSlots > 0 && !queue.isEmpty()) {
                QueueEntry queueEntry = queue.poll();
                if (queueEntry == null) break;

                if (queueEntry.isVirtual()) {
                    playerTargetServerMap.remove(queueEntry.getUuid());
                    if (virtualQueueHandler != null) {
                        virtualQueueHandler.onVirtualPlayerAllowed(queueEntry.getUuid(), serverName, queueEntry.getSourceServer());
                    } else if (plugin.isDebug()) {
                        plugin.getLogger().warning(languageManager.getLogMessage("virtual-queue-no-handler", "uuid", queueEntry.getUuid().toString()));
                    }
                    availableSlots--;
                    continue;
                }

                Player player = plugin.getServer().getPlayer(queueEntry.getUuid());
                if (player == null || !player.isOnline()) {
                    playerTargetServerMap.remove(queueEntry.getUuid());
                    continue;
                }

                allowedPlayers.add(queueEntry.getUuid());
                playerTargetServerMap.remove(queueEntry.getUuid());
                player.sendMessage(languageManager.getMessage("entering"));
                if (plugin.isDebug()) {
                    plugin.getLogger().info(languageManager.getLogMessage("queue-player-allowed", "player", player.getName()));
                }

                if (plugin.getQueueItemListener() != null) {
                    plugin.getQueueItemListener().removeAllQueueItems(player);
                }

                messenger.connectPlayerToServer(player, serverName);
                availableSlots--;
            }
        }
    }

    private void notifyServerQueuePlayers(String serverName, String message) {
        PriorityQueue<QueueEntry> queue = serverQueues.get(serverName);
        if (queue == null) return;
        for (QueueEntry entry : queue) {
            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
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

    public interface VirtualQueueHandler {
        void onVirtualPlayerAllowed(UUID uuid, String targetServer, String sourceServer);
    }

    static class QueueEntry {
        private final UUID uuid;
        private final int priority;
        private final long timestamp;
        private final String sourceServer;

        QueueEntry(UUID uuid, int priority, long timestamp) {
            this(uuid, priority, timestamp, null);
        }

        QueueEntry(UUID uuid, int priority, long timestamp, String sourceServer) {
            this.uuid = uuid;
            this.priority = priority;
            this.timestamp = timestamp;
            this.sourceServer = sourceServer;
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

        String getSourceServer() {
            return sourceServer;
        }

        boolean isVirtual() {
            return sourceServer != null;
        }
    }
}
