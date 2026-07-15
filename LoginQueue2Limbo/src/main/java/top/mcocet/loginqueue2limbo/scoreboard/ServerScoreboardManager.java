package top.mcocet.loginqueue2limbo.scoreboard;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.scheduler.LimboTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器状态计分板管理器 (Limbo 版本)
 * 使用 Limbo 的 Adventure API 显示服务器状态信息
 * 通过 Action Bar 或 Chat 消息显示（Limbo 不支持原生计分板）
 */
public class ServerScoreboardManager {

    private final LoginQueue2Limbo plugin;
    private final BungeeMessenger messenger;
    private final LanguageManager languageManager;
    private final boolean enabled;
    private final long rotateInterval;
    private final long updateInterval;
    private final List<String> serverNames;
    private int currentServerIndex = 0;

    // 存储已显示计分板的玩家
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();

    public ServerScoreboardManager(LoginQueue2Limbo plugin, BungeeMessenger messenger) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.languageManager = plugin.getLanguageManager();
        this.enabled = plugin.getConfigValueBoolean("scoreboard.enabled", true);
        this.rotateInterval = plugin.getConfigValueLong("scoreboard.rotate-interval", 10) * 20L;
        this.updateInterval = plugin.getConfigValueLong("scoreboard.update-interval", 2) * 20L;
        this.serverNames = plugin.getConfigValueStringList("scoreboard.servers");

        if (enabled) {
            startTasks();
        }
    }

    private void startTasks() {
        // 更新显示任务
        Limbo.getInstance().getScheduler().runTaskTimer(plugin, new LimboTask() {
            @Override
            public void run() {
                if (!Limbo.getInstance().isRunning()) return;
                updateAllDisplays();
            }
        }, updateInterval, updateInterval);

        // 轮换服务器任务
        if (serverNames.size() > 1) {
            Limbo.getInstance().getScheduler().runTaskTimer(plugin, new LimboTask() {
                @Override
                public void run() {
                    if (!Limbo.getInstance().isRunning()) return;
                    currentServerIndex = (currentServerIndex + 1) % serverNames.size();
                    updateAllDisplays();
                }
            }, rotateInterval, rotateInterval);
        }
    }

    /**
     * 为玩家启用状态显示
     */
    public void showScoreboard(Player player) {
        if (!enabled) return;
        activePlayers.add(player.getUniqueId());
        sendDisplay(player);
    }

    /**
     * 隐藏玩家状态显示
     */
    public void hideScoreboard(Player player) {
        activePlayers.remove(player.getUniqueId());
        // 发送空消息清除显示
        player.sendActionBar(Component.empty());
    }

    /**
     * 更新所有玩家的显示
     */
    private void updateAllDisplays() {
        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player player = Limbo.getInstance().getPlayer(uuid);
            if (player == null) {
                activePlayers.remove(uuid);
                continue;
            }
            sendDisplay(player);
        }
    }

    /**
     * 发送显示给单个玩家
     * 使用 Action Bar 显示当前服务器状态
     */
    private void sendDisplay(Player player) {
        if (serverNames.isEmpty()) return;

        String currentServer = serverNames.get(currentServerIndex);
        BungeeMessenger.ServerStatus status = messenger.getServerStatus(currentServer);

        // 构建 Action Bar 消息
        StringBuilder sb = new StringBuilder();
        sb.append(languageManager.getMessage("scoreboard-prefix")).append(currentServer).append(" ");

        if (status != null && status.isOnline()) {
            sb.append(languageManager.getMessage("scoreboard-status-online")).append(" | ");
            sb.append(languageManager.getMessage("scoreboard-players", "online", String.valueOf(status.getOnlinePlayers()), "max", String.valueOf(status.getMaxPlayers()))).append(" | ");
            sb.append(languageManager.getMessage("scoreboard-tps", "tps", String.format("%.1f", status.getTps()))).append(" | ");
            sb.append(languageManager.getMessage("scoreboard-memory", "used", String.valueOf(status.getUsedMemory()), "max", String.valueOf(status.getMaxMemory())));
        } else {
            sb.append(languageManager.getMessage("scoreboard-status-offline")).append(" | ").append(languageManager.getMessage("scoreboard-na"));
        }

        Component message = LegacyComponentSerializer.legacySection().deserialize(sb.toString());
        player.sendActionBar(message);

        // 同时发送聊天栏的详细服务器列表（每30秒一次，避免刷屏）
        // 这里简化处理，只发送 Action Bar
    }

    /**
     * 发送详细的服务器列表到聊天栏
     */
    public void sendServerList(Player player) {
        List<Component> messages = new ArrayList<>();
        messages.add(LegacyComponentSerializer.legacySection().deserialize(languageManager.getMessage("scoreboard-list-header")));

        for (String serverName : serverNames) {
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
            if (status != null && status.isOnline()) {
                String line = languageManager.getMessage("scoreboard-list-online",
                        "server", serverName,
                        "online", String.valueOf(status.getOnlinePlayers()),
                        "max", String.valueOf(status.getMaxPlayers()),
                        "tps", String.format("%.1f", status.getTps()),
                        "used", String.valueOf(status.getUsedMemory()),
                        "maxmem", String.valueOf(status.getMaxMemory()));
                messages.add(LegacyComponentSerializer.legacySection().deserialize(line));
            } else {
                messages.add(LegacyComponentSerializer.legacySection().deserialize(languageManager.getMessage("scoreboard-list-offline", "server", serverName)));
            }
        }

        messages.add(LegacyComponentSerializer.legacySection().deserialize(languageManager.getMessage("scoreboard-list-footer")));

        for (Component msg : messages) {
            player.sendMessage(msg);
        }
    }

    /**
     * 获取当前显示的服务器索引
     */
    public int getCurrentServerIndex() {
        return currentServerIndex;
    }

    /**
     * 设置当前显示的服务器索引
     */
    public void setCurrentServerIndex(int index) {
        if (index >= 0 && index < serverNames.size()) {
            this.currentServerIndex = index;
            updateAllDisplays();
        }
    }

    /**
     * 获取配置的服务器列表
     */
    public List<String> getServerNames() {
        return new ArrayList<>(serverNames);
    }

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 停止所有任务
     */
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player player = Limbo.getInstance().getPlayer(uuid);
            if (player != null) {
                hideScoreboard(player);
            }
        }
        activePlayers.clear();
    }
}
