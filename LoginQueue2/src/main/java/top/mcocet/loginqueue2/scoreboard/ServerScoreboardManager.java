package top.mcocet.loginqueue2.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.util.LanguageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器状态计分板管理器
 * 使用 Bukkit 原生 Scoreboard API 显示各服务器状态信息
 */
public class ServerScoreboardManager {

    private final JavaPlugin plugin;
    private final BungeeMessenger messenger;
    private final LanguageManager languageManager;
    private final boolean enabled;
    private final long rotateInterval;
    private final List<String> serverNames;
    private int currentServerIndex = 0;

    // 存储每个玩家的计分板
    private final Map<UUID, Scoreboard> playerScoreboards = new ConcurrentHashMap<>();

    public ServerScoreboardManager(JavaPlugin plugin, BungeeMessenger messenger) {
        this.plugin = plugin;
        this.messenger = messenger;
        this.languageManager = ((LoginQueue2) plugin).getLanguageManager();
        this.enabled = plugin.getConfig().getBoolean("scoreboard.enabled", true);
        this.rotateInterval = plugin.getConfig().getLong("scoreboard.rotate-interval", 10) * 20L;
        this.serverNames = plugin.getConfig().getStringList("scoreboard.servers");

        if (enabled) {
            startTasks();
        }
    }

    private void startTasks() {
        // 更新计分板内容任务（每2秒）
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }
                updateAllScoreboards();
            }
        }.runTaskTimer(plugin, 20L, 40L);

        // 轮换显示服务器任务
        if (serverNames.size() > 1) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!plugin.isEnabled()) {
                        cancel();
                        return;
                    }
                    currentServerIndex = (currentServerIndex + 1) % serverNames.size();
                    updateAllScoreboards();
                }
            }.runTaskTimer(plugin, rotateInterval, rotateInterval);
        }
    }

    /**
     * 为玩家创建并显示计分板
     */
    public void showScoreboard(Player player) {
        if (!enabled) return;

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("lq2servers", "dummy",
                languageManager.getMessage("scoreboard-title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        player.setScoreboard(scoreboard);
        playerScoreboards.put(player.getUniqueId(), scoreboard);

        updateScoreboard(player, scoreboard, objective);
    }

    /**
     * 隐藏玩家计分板
     */
    public void hideScoreboard(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /**
     * 更新所有玩家的计分板
     */
    private void updateAllScoreboards() {
        for (Map.Entry<UUID, Scoreboard> entry : playerScoreboards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            Scoreboard scoreboard = entry.getValue();
            Objective objective = scoreboard.getObjective("lq2servers");
            if (objective == null) {
                objective = scoreboard.registerNewObjective("lq2servers", "dummy",
                        languageManager.getMessage("scoreboard-title"));
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }

            // 清除旧行
            for (String entryName : scoreboard.getEntries()) {
                scoreboard.resetScores(entryName);
            }

            updateScoreboard(player, scoreboard, objective);
        }
    }

    /**
     * 更新单个玩家的计分板内容
     */
    private void updateScoreboard(Player player, Scoreboard scoreboard, Objective objective) {
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.DARK_GRAY + "═══════════");

        // 显示当前轮到的服务器（大字体显示）
        if (!serverNames.isEmpty()) {
            String currentServer = serverNames.get(currentServerIndex);
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(currentServer);

            lines.add(ChatColor.AQUA + "▶ " + currentServer);
            if (status != null && status.isOnline()) {
                lines.add(ChatColor.GREEN + "  " + languageManager.getMessage("scoreboard-status-online"));
                lines.add(ChatColor.YELLOW + "  " + languageManager.getMessage("scoreboard-players", "online", String.valueOf(status.getOnlinePlayers()), "max", String.valueOf(status.getMaxPlayers())));
                lines.add(ChatColor.YELLOW + "  " + languageManager.getMessage("scoreboard-tps", "tps", String.format("%.1f", status.getTps())));
                lines.add(ChatColor.YELLOW + "  " + languageManager.getMessage("scoreboard-memory", "used", String.valueOf(status.getUsedMemory()), "max", String.valueOf(status.getMaxMemory())));
            } else {
                lines.add(ChatColor.RED + "  " + languageManager.getMessage("scoreboard-status-offline"));
                lines.add(ChatColor.GRAY + "  " + languageManager.getMessage("scoreboard-players-na"));
                lines.add(ChatColor.GRAY + "  " + languageManager.getMessage("scoreboard-tps-na"));
                lines.add(ChatColor.GRAY + "  " + languageManager.getMessage("scoreboard-memory-na"));
            }
        }

        lines.add(ChatColor.DARK_GRAY + "═══════════");

        // 显示所有服务器简要状态
        lines.add(ChatColor.GOLD + languageManager.getMessage("scoreboard-all-servers"));
        for (String serverName : serverNames) {
            BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
            if (status != null && status.isOnline()) {
                lines.add(ChatColor.GREEN + "● " + serverName + " " + status.getOnlinePlayers() + "/" + status.getMaxPlayers());
            } else {
                lines.add(ChatColor.RED + "● " + serverName + " " + languageManager.getMessage("scoreboard-offline"));
            }
        }

        lines.add(ChatColor.DARK_GRAY + "═══════════");
        lines.add(ChatColor.GRAY + "LQ2 v" + plugin.getDescription().getVersion());

        // 设置分数（倒序显示，第一行分数最高）
        int score = lines.size();
        for (String line : lines) {
            String uniqueLine = makeUnique(line, score);
            Score scoreObj = objective.getScore(uniqueLine);
            scoreObj.setScore(score);
            score--;
        }
    }

    /**
     * 使每行唯一（Bukkit 计分板要求 entry 名称唯一）
     */
    private String makeUnique(String line, int score) {
        StringBuilder prefix = new StringBuilder();
        int val = score;
        while (val > 0) {
            prefix.append(ChatColor.COLOR_CHAR).append((char) ('0' + (val % 10)));
            val /= 10;
        }
        if (prefix.length() == 0) {
            prefix.append(ChatColor.COLOR_CHAR).append('0');
        }
        return prefix.toString() + ChatColor.RESET + line;
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
            updateAllScoreboards();
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
        for (UUID uuid : new ArrayList<>(playerScoreboards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                hideScoreboard(player);
            }
        }
        playerScoreboards.clear();
    }
}
