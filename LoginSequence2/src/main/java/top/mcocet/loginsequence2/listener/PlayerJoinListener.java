package top.mcocet.loginsequence2.listener;

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
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.bungee.BungeeMessenger;
import top.mcocet.loginsequence2.util.LanguageManager;

import java.util.*;

public class PlayerJoinListener implements Listener {

    private final LoginSequence plugin;
    private final BungeeMessenger messenger;
    private final List<String> priorityList;
    private final int defaultPriority;
    private final double threshold;
    private final LanguageManager languageManager;

    // 等待队列：按优先级排序
    private final PriorityQueue<QueueEntry> waitingQueue;
    // 已允许进入的玩家
    private final Set<UUID> allowedPlayers = new HashSet<>();

    public PlayerJoinListener(LoginSequence plugin, BungeeMessenger messenger) {
        this.plugin = plugin;
        this.messenger = messenger;
        FileConfiguration config = plugin.getConfig();
        this.priorityList = config.getStringList("queue.priority");
        this.defaultPriority = config.getInt("queue.default-priority", 0);
        this.threshold = Math.max(0.0, Math.min(1.0, config.getDouble("queue.threshold", 0.8)));
        this.languageManager = plugin.getLanguageManager();

        // 优先级数字越大越靠前
        this.waitingQueue = new PriorityQueue<>(Collections.reverseOrder(Comparator.comparingInt(QueueEntry::getPriority)));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        // 设置玩家游戏模式
        applyGameMode(player);

        // 设置玩家出生点
        teleportToSpawn(player);

        // 如果已经允许进入，直接放行
        if (allowedPlayers.contains(uuid)) {
            return;
        }

        boolean autoQueue = plugin.getConfig().getBoolean("queue.auto-queue", true);
        if (!autoQueue) {
            player.sendMessage(languageManager.getMessage("manual-queue-hint"));
            return;
        }

        // 延迟执行，等待 BungeeCord 数据刷新和锁定时间
        long lockTime = plugin.getConfig().getLong("queue.lock-time", 3);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }

                // 先判断主服务器是否在线
                if (!messenger.isMainServerOnline()) {
                    player.sendMessage(languageManager.getMessage("main-offline"));
                    return;
                }

                // 已在队列中则不再重复添加
                if (isInQueue(uuid)) {
                    return;
                }

                // 计算玩家优先级并入队
                int priority = calculatePriority(player);
                waitingQueue.offer(new QueueEntry(uuid, priority));

                // 通知玩家排队位置
                sendQueueStatus(player, uuid);

                // 尝试放行队列中的玩家
                processQueue();
            }
        }.runTaskLater(plugin, lockTime * 20L);
    }

    private void sendQueueStatus(Player player, UUID uuid) {
        int position = getPosition(uuid);
        int online = messenger.getMainServerPlayerCount();
        int max = messenger.getMainServerMaxPlayers();
        player.sendMessage(languageManager.getMessage("waiting",
                "position", String.valueOf(position),
                "online", String.valueOf(online),
                "max", String.valueOf(max)));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        allowedPlayers.remove(uuid);
        waitingQueue.removeIf(entry -> entry.getUuid().equals(uuid));
        processQueue();
    }

    private int calculatePriority(Player player) {
        for (int i = 0; i < priorityList.size(); i++) {
            String rule = priorityList.get(i);
            if (rule == null || rule.isEmpty()) continue;

            String[] parts = rule.split(":", 2);
            if (parts.length != 2) continue;

            String type = parts[0].toLowerCase();
            String value = parts[1];

            if ("permission".equals(type) && player.hasPermission(value)) {
                // 列表中越靠前，优先级越高（数值越大）
                return priorityList.size() - i;
            }
            if ("name".equals(type) && player.getName().equalsIgnoreCase(value)) {
                return priorityList.size() - i;
            }
        }
        return defaultPriority;
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

        int priority = calculatePriority(player);
        waitingQueue.offer(new QueueEntry(player.getUniqueId(), priority));
        sendQueueStatus(player, player.getUniqueId());
        processQueue();
    }

    public void allowPlayerDirectly(Player player) {
        if (isInQueue(player.getUniqueId())) {
            waitingQueue.removeIf(entry -> entry.getUuid().equals(player.getUniqueId()));
        }
        allowedPlayers.add(player.getUniqueId());
        player.sendMessage(languageManager.getMessage("entering"));
        messenger.connectToMainServer(player);
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
            plugin.getLogger().warning("配置的游戏模式 " + modeName + " 无效，使用默认 ADVENTURE 模式。");
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
        player.teleport(spawnLocation);
    }

    private void processQueue() {
        if (!messenger.isMainServerOnline()) {
            return;
        }

        int maxOnline = messenger.getMainServerMaxPlayers();
        int mainOnline = messenger.getMainServerPlayerCount();

        // 超过阈值时暂停放行
        if (maxOnline > 0 && (double) mainOnline / maxOnline >= threshold) {
            notifyWaitingPlayers(languageManager.getMessage("threshold-reached"));
            return;
        }

        int availableSlots = Math.max(0, maxOnline - mainOnline);

        while (availableSlots > 0 && !waitingQueue.isEmpty()) {
            QueueEntry entry = waitingQueue.poll();
            if (entry == null) break;

            Player player = plugin.getServer().getPlayer(entry.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }

            allowedPlayers.add(entry.getUuid());
            player.sendMessage(languageManager.getMessage("entering"));
            messenger.connectToMainServer(player);
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

        QueueEntry(UUID uuid, int priority) {
            this.uuid = uuid;
            this.priority = priority;
        }

        UUID getUuid() {
            return uuid;
        }

        int getPriority() {
            return priority;
        }
    }
}
