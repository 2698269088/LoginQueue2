package top.mcocet.loginsequence2limbo.listener;

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
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;
import top.mcocet.loginsequence2limbo.auth.AuthManager;
import top.mcocet.loginsequence2limbo.auth.AuthRestrictionListener;
import top.mcocet.loginsequence2limbo.bungee.BungeeMessenger;
import top.mcocet.loginsequence2limbo.util.LanguageManager;

import java.util.*;

public class PlayerJoinListener implements Listener {

    private final LoginSequence2Limbo plugin;
    private final BungeeMessenger messenger;
    private final AuthManager authManager;
    private final AuthRestrictionListener authRestrictionListener;
    private final List<String> priorityList;
    private final int defaultPriority;
    private final double threshold;
    private final LanguageManager languageManager;

    private final PriorityQueue<QueueEntry> waitingQueue;
    private final Set<UUID> allowedPlayers = new HashSet<>();

    public PlayerJoinListener(LoginSequence2Limbo plugin, BungeeMessenger messenger,
                              AuthManager authManager, AuthRestrictionListener authRestrictionListener) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.authManager = authManager;
        this.authRestrictionListener = authRestrictionListener;
        this.priorityList = plugin.getConfigValueStringList("queue.priority");
        this.defaultPriority = plugin.getConfigValueInt("queue.default-priority", 0);
        this.threshold = Math.max(0.0, Math.min(1.0, plugin.getConfigValueDouble("queue.threshold", 0.8)));
        this.languageManager = plugin.getLanguageManager();
        this.waitingQueue = new PriorityQueue<>(Collections.reverseOrder(Comparator.comparingInt(QueueEntry::getPriority)));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        applyGameMode(player);
        teleportToSpawn(player);

        if (allowedPlayers.contains(uuid)) {
            return;
        }

        // 认证功能启用时，未认证玩家不自动入队
        if (authManager.isEnabled()) {
            authRestrictionListener.removeAuthenticated(uuid);
            if (!authManager.isRegistered(player.getName())) {
                player.sendMessage("[LoginSequence] 欢迎来到服务器！请使用 /register <密码> <确认密码> 注册账号");
            } else {
                player.sendMessage("[LoginSequence] 请使用 /login <密码> 登录");
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
                    int priority = calculatePriority(player);
                    waitingQueue.offer(new QueueEntry(uuid, priority));
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
                            int priority = calculatePriority(player);
                            waitingQueue.offer(new QueueEntry(uuid, priority));
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
        UUID uuid = event.getPlayer().getUniqueId();
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

    private int calculatePriority(Player player) {
        for (int i = 0; i < priorityList.size(); i++) {
            String rule = priorityList.get(i);
            if (rule == null || rule.isEmpty()) continue;
            String[] parts = rule.split(":", 2);
            if (parts.length != 2) continue;
            String type = parts[0].toLowerCase();
            String value = parts[1];
            if ("permission".equals(type) && player.hasPermission(value)) {
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

        // 认证模式下，必须先通过登录认证才能入队
        if (authManager.isEnabled() && authRestrictionListener != null) {
            if (!authRestrictionListener.isAuthenticated(player.getUniqueId())) {
                player.sendMessage("§c请先登录后再加入队列");
                return;
            }
        }

        // 通知代理端玩家登录成功（允许使用 /server 命令）
        if (authManager.isEnabled()) {
            messenger.notifyLoginSuccess(player);
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

    private void applyGameMode(Player player) {
        if (!plugin.getConfigValueBoolean("queue.set-gamemode", true)) {
            return;
        }
        String modeName = plugin.getConfigValueString("queue.gamemode", "ADVENTURE");
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(modeName.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            Limbo.getInstance().getConsole().sendMessage("[LoginSequence2Limbo] 配置的游戏模式 " + modeName + " 无效，使用默认 ADVENTURE 模式。");
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
