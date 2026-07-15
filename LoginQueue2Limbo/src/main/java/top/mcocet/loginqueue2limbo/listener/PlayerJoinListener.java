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
    private final Set<UUID> allowedPlayers = new HashSet<>();
    // 队列手动暂停状态
    private boolean queuePaused = false;

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
        waitingQueue.removeIf(entry -> entry.getUuid().equals(uuid));
        if (authRestrictionListener != null) {
            authRestrictionListener.removeAuthenticated(uuid);
        }
        processQueue();
    }

    private void sendQueueStatus(Player player, UUID uuid) {
        int position = getPosition(uuid);
        int online = messenger.getMainServerPlayerCount();
        int max = messenger.getMainServerMaxPlayers();
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
        player.sendMessage(languageManager.getMessage("waiting",
                "position", String.valueOf(position),
                "online", String.valueOf(online),
                "max", String.valueOf(max)));
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
        if (authManager.isEnabled()) {
            messenger.notifyLoginSuccess(player);
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
        messenger.connectToOptimalServer(player);
    }

    public List<String> getQueuePlayerNames() {
        List<String> names = new ArrayList<>();
        for (QueueEntry entry : waitingQueue) {
            for (Player player : Limbo.getInstance().getPlayers()) {
                if (player.getUniqueId().equals(entry.getUuid())) {
                    names.add(player.getName());
                    break;
                }
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
