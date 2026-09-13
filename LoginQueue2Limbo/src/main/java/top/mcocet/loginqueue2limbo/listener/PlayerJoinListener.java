package top.mcocet.loginqueue2limbo.listener;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerJoinEvent;
import com.loohp.limbo.events.player.PlayerQuitEvent;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.scheduler.LimboTask;
import com.loohp.limbo.utils.GameMode;
import com.loohp.limbo.location.Location;
import com.loohp.limbo.world.World;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.auth.AuthManager;
import top.mcocet.loginqueue2limbo.auth.AuthRestrictionListener;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.queue.PriorityManager;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.*;

public class PlayerJoinListener implements Listener {

    private final LoginQueue2Limbo plugin;
    private final BungeeMessenger messenger;
    private final AuthManager authManager;
    private final AuthRestrictionListener authRestrictionListener;
    private final double threshold;
    private final LanguageManager languageManager;
    private final PriorityManager priorityManager;

    private final PriorityQueue<QueueEntry> waitingQueue;
    // 多服务器独立队列：仅在多服务器跳转模式启用时使用
    private final Map<String, PriorityQueue<QueueEntry>> serverQueues = new HashMap<>();
    // 记录玩家所属目标服务器
    private final Map<UUID, String> playerTargetServerMap = new HashMap<>();
    private final Set<UUID> allowedPlayers = new HashSet<>();
    // 队列手动暂停状态
    private boolean queuePaused = false;
    // 虚拟队列处理器（当虚拟玩家被放行时回调）
    private VirtualQueueHandler virtualQueueHandler;

    public PlayerJoinListener(LoginQueue2Limbo plugin, BungeeMessenger messenger,
                              AuthManager authManager, AuthRestrictionListener authRestrictionListener) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.authManager = authManager;
        this.authRestrictionListener = authRestrictionListener;
        this.threshold = Math.max(0.0, Math.min(1.0, plugin.getConfigValueDouble("queue.threshold", 0.8)));
        this.languageManager = plugin.getLanguageManager();
        this.priorityManager = new PriorityManager(plugin);

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
     * 判断当前是否启用每服独立队列模式（Limbo 不存在 WORLD 模式）
     */
    public boolean isPerServerQueueMode() {
        return plugin.getConfigValueBoolean("queue.per-server-queue", false)
                && plugin.getConfigValueBoolean("udp-sync.enabled", false)
                && plugin.getConfigValueBoolean("udp-sync.server-selector.enabled", false);
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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        applyGameMode(player);
        teleportToSpawn(player);

        // 显示服务器状态计分板
        if (plugin.getScoreboardManager() != null && plugin.getScoreboardManager().isEnabled()) {
            plugin.getScoreboardManager().showScoreboard(player);
        }

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

        boolean autoQueue = plugin.getConfigValueBoolean("queue.auto-queue", true);
        if (!autoQueue) {
            player.sendMessage(languageManager.getMessage("manual-queue-hint"));
            return;
        }

        long lockTime = plugin.getConfigValueLong("queue.lock-time", 3);
        Limbo.getInstance().getScheduler().runTaskLater(plugin, new LimboTask() {
            @Override
            public void run() {
                if (!player.isValid()) {
                    return;
                }

                if (messenger.isMainServerOnline()) {
                    if (isInQueue(uuid)) {
                        return;
                    }
                    int priority = priorityManager.calculatePriority(player);
                    waitingQueue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));
                    sendQueueStatus(player, uuid);
                    processQueue();
                    return;
                }

                player.sendMessage(languageManager.getMessage("checking-main-server"));
                messenger.checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
                    Limbo.getInstance().getScheduler().runTask(plugin, new LimboTask() {
                        @Override
                        public void run() {
                            if (throwable != null || !online) {
                                player.sendMessage(languageManager.getMessage("main-offline"));
                                return;
                            }
                            if (!player.isValid()) {
                                return;
                            }
                            if (isInQueue(uuid)) {
                                return;
                            }
                            int priority = priorityManager.calculatePriority(player);
                            waitingQueue.offer(new QueueEntry(uuid, priority, System.currentTimeMillis()));
                            sendQueueStatus(player, uuid);
                            processQueue();
                        }
                    });
                });
            }
        }, lockTime * 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // 隐藏计分板
        if (plugin.getScoreboardManager() != null) {
            plugin.getScoreboardManager().hideScoreboard(player);
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

    private void sendQueueStatus(Player player, UUID uuid) {
        int position = getPosition(uuid);

        String targetServer = playerTargetServerMap.get(uuid);
        int online;
        int max;

        if (isPerServerQueueMode() && targetServer != null) {
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(targetServer);
            if (status != null && status.isOnline()) {
                online = status.getOnlinePlayers();
                max = status.getMaxPlayers();
            } else {
                online = 0;
                max = plugin.getConfigValueInt("queue.max-online", 50);
            }
        } else {
            online = messenger.getMainServerPlayerCount();
            max = messenger.getMainServerMaxPlayers();
            int totalOnline = 0;
            int totalMax = 0;
            for (BungeeMessenger.ServerStatus status : messenger.getAllServerStatus().values()) {
                if (status.isOnline()) {
                    totalOnline += status.getOnlinePlayers();
                    totalMax += status.getMaxPlayers();
                }
            }
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

        // 认证模式下，必须先通过登录认证才能入队
        if (authManager.isEnabled() && authRestrictionListener != null) {
            if (!authRestrictionListener.isAuthenticated(player.getUniqueId())) {
                player.sendMessage(languageManager.getMessage("auth-please-login-first"));
                return;
            }
        }

        // 通知代理端玩家登录成功（允许使用 /server 命令）
        if (authManager.isEnabled()) {
            messenger.notifyLoginSuccess(player);
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

        if (isPerServerQueueMode()) {
            // 多服独立队列模式：跳转到目标服务器
            String targetServer = playerTargetServerMap.get(player.getUniqueId());
            if (targetServer != null) {
                messenger.connectPlayerToServer(player, targetServer);
            } else {
                messenger.connectToOptimalServer(player);
            }
        } else {
            messenger.connectToOptimalServer(player);
        }
    }

    public List<String> getQueuePlayerNames() {
        List<String> names = new ArrayList<>();
        if (isPerServerQueueMode()) {
            for (Map.Entry<String, PriorityQueue<QueueEntry>> entry : serverQueues.entrySet()) {
                for (QueueEntry queueEntry : entry.getValue()) {
                    for (Player player : Limbo.getInstance().getPlayers()) {
                        if (player.getUniqueId().equals(queueEntry.getUuid())) {
                            names.add(player.getName() + " [" + entry.getKey() + "]");
                            break;
                        }
                    }
                }
            }
        } else {
            for (QueueEntry entry : waitingQueue) {
                for (Player player : Limbo.getInstance().getPlayers()) {
                    if (player.getUniqueId().equals(entry.getUuid())) {
                        names.add(player.getName());
                        break;
                    }
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
        if (!plugin.getConfigValueBoolean("queue.set-gamemode", true)) {
            return;
        }
        String modeName = plugin.getConfigValueString("queue.gamemode", "ADVENTURE");
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(modeName.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            Limbo.getInstance().getConsole().sendMessage(plugin.getLanguageManager().getLogMessage("invalid-gamemode", "mode", modeName));
            gameMode = GameMode.ADVENTURE;
        }
        player.setGamemode(gameMode);
    }

    private void teleportToSpawn(Player player) {
        String worldName = plugin.getConfigValueString("queue.spawn.world", "world");
        World world = Limbo.getInstance().getWorld(worldName);
        if (world == null) {
            world = player.getWorld();
        }

        double centerX = plugin.getConfigValueDouble("queue.spawn.x", 0.0);
        double centerY = plugin.getConfigValueDouble("queue.spawn.y", 64.0);
        double centerZ = plugin.getConfigValueDouble("queue.spawn.z", 0.0);
        float pitch = (float) plugin.getConfigValueDouble("queue.spawn.pitch", 0.0);
        float yaw = (float) plugin.getConfigValueDouble("queue.spawn.yaw", 0.0);
        double radius = plugin.getConfigValueDouble("queue.spawn.radius", 5.0);

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
        player.teleport(spawnLocation);
    }

    public void processQueue() {
        if (isPerServerQueueMode()) {
            processPerServerQueues();
            return;
        }

        int totalMax = 0;
        int totalOnline = 0;
        boolean anyOnline = false;
        for (BungeeMessenger.ServerStatus status : messenger.getAllServerStatus().values()) {
            if (status.isOnline()) {
                anyOnline = true;
                int max = status.getMaxPlayers();
                int online = status.getOnlinePlayers();
                if (max <= 0) {
                    max = plugin.getConfigValueInt("queue.max-online", 50);
                }
                totalMax += max;
                totalOnline += online;
            }
        }

        // 手动暂停队列
        if (queuePaused) {
            return;
        }

        if (!anyOnline) {
            return;
        }

        if (totalMax > 0 && (double) totalOnline / totalMax >= threshold) {
            notifyWaitingPlayers(languageManager.getMessage("threshold-reached"));
            return;
        }

        int availableSlots = Math.max(0, totalMax - totalOnline);

        while (availableSlots > 0 && !waitingQueue.isEmpty()) {
            QueueEntry entry = waitingQueue.poll();
            if (entry == null) break;

            Player player = null;
            for (Player p : Limbo.getInstance().getPlayers()) {
                if (p.getUniqueId().equals(entry.getUuid())) {
                    player = p;
                    break;
                }
            }
            if (player == null || !player.isValid()) {
                continue;
            }

            allowedPlayers.add(entry.getUuid());
            player.sendMessage(languageManager.getMessage("entering"));
            messenger.connectToOptimalServer(player);
            availableSlots--;
        }
    }

    private void processPerServerQueues() {
        if (queuePaused) {
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
                max = plugin.getConfigValueInt("queue.max-online", 50);
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
                        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + languageManager.getLogMessage("virtual-queue-no-handler", "uuid", queueEntry.getUuid().toString()));
                    }
                    availableSlots--;
                    continue;
                }

                Player player = null;
                for (Player p : Limbo.getInstance().getPlayers()) {
                    if (p.getUniqueId().equals(queueEntry.getUuid())) {
                        player = p;
                        break;
                    }
                }
                if (player == null || !player.isValid()) {
                    playerTargetServerMap.remove(queueEntry.getUuid());
                    continue;
                }

                allowedPlayers.add(queueEntry.getUuid());
                playerTargetServerMap.remove(queueEntry.getUuid());
                player.sendMessage(languageManager.getMessage("entering"));
                messenger.connectPlayerToServer(player, serverName);
                availableSlots--;
            }
        }
    }

    private void notifyServerQueuePlayers(String serverName, String message) {
        PriorityQueue<QueueEntry> queue = serverQueues.get(serverName);
        if (queue == null) return;
        for (QueueEntry entry : queue) {
            for (Player player : Limbo.getInstance().getPlayers()) {
                if (player.getUniqueId().equals(entry.getUuid())) {
                    player.sendMessage(message);
                    break;
                }
            }
        }
    }

    private void notifyWaitingPlayers(String message) {
        for (QueueEntry entry : waitingQueue) {
            for (Player player : Limbo.getInstance().getPlayers()) {
                if (player.getUniqueId().equals(entry.getUuid())) {
                    player.sendMessage(message);
                    break;
                }
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
