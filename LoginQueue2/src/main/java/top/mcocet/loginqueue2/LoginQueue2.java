package top.mcocet.loginqueue2;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginqueue2.auth.AuthCommand;
import top.mcocet.loginqueue2.auth.AuthManager;
import top.mcocet.loginqueue2.auth.AuthMeCompatManager;
import top.mcocet.loginqueue2.auth.AuthRestrictionListener;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.command.JoinCommand;
import top.mcocet.loginqueue2.command.LoginQueue2Command;
import top.mcocet.loginqueue2.listener.DimensionListener;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.listener.PerformanceListener;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.listener.PlayerMoveListener;
import top.mcocet.loginqueue2.listener.PlayerRestrictionListener;
import top.mcocet.loginqueue2.listener.QueueItemListener;
import top.mcocet.loginqueue2.listener.WorldInventoryListener;
import top.mcocet.loginqueue2.scoreboard.ServerScoreboardManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;
import top.mcocet.loginqueue2.world.LoginWorldManager;

public final class LoginQueue2 extends JavaPlugin {

    private BungeeMessenger messenger;
    private PlayerJoinListener playerJoinListener;
    private LanguageManager languageManager;
    private AuthManager authManager;
    private AuthRestrictionListener authRestrictionListener;
    private AuthMeCompatManager authMeCompatManager;
    private PlayerRestrictionListener playerRestrictionListener;
    private ServerScoreboardManager scoreboardManager;
    private LoginWorldManager loginWorldManager;
    private QueueItemListener queueItemListener;
    private WorldInventoryListener worldInventoryListener;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.debug = getConfig().getBoolean("debug", false);
        this.languageManager = new LanguageManager(this);

        // 检测工作模式
        boolean worldMode = "WORLD".equalsIgnoreCase(getConfig().getString("work-mode", "PROXY"));

        if (worldMode) {
            getLogger().info(languageManager.getLogMessage("world-mode-enabled"));
        } else {
            getLogger().info(languageManager.getLogMessage("proxy-mode-enabled"));
        }

        boolean enableBungeeExtension = !worldMode && getConfig().getBoolean("enable-bungee-extension", true);
        this.messenger = new BungeeMessenger(this, enableBungeeExtension);
        if (!worldMode) {
            getLogger().info(languageManager.getLogMessage("bungee-extension-status", "status", languageManager.getLogMessage(enableBungeeExtension ? "enabled" : "disabled")));
        }
        this.authManager = new AuthManager(this);
        this.authRestrictionListener = new AuthRestrictionListener(this, authManager);
        getServer().getPluginManager().registerEvents(authRestrictionListener, this);

        // 初始化 AuthMe 兼容模式（在内置 auth 未启用时）
        this.authMeCompatManager = new AuthMeCompatManager(this);

        // 初始化登录世界管理器（WORLD 模式）
        this.loginWorldManager = new LoginWorldManager(this);
        getServer().getPluginManager().registerEvents(loginWorldManager, this);
        loginWorldManager.init();

        this.playerJoinListener = new PlayerJoinListener(this, messenger, authManager, authRestrictionListener, authMeCompatManager);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);

        // WORLD 模式下禁用计分板（不需要显示 BungeeCord 服务器状态）
        if (!worldMode && getConfig().getBoolean("scoreboard.enabled", true)) {
            this.scoreboardManager = new ServerScoreboardManager(this, messenger);
        }

        this.playerRestrictionListener = new PlayerRestrictionListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers());
        getServer().getPluginManager().registerEvents(playerRestrictionListener, this);

        this.queueItemListener = new QueueItemListener(this, playerJoinListener);
        getServer().getPluginManager().registerEvents(queueItemListener, this);

        // WORLD 模式下注册背包管理监听器
        if (worldMode) {
            this.worldInventoryListener = new WorldInventoryListener(this);
            getServer().getPluginManager().registerEvents(worldInventoryListener, this);
            // 启动主世界未授权玩家监控任务（每 5 秒检查一次）
            startMainWorldMonitorTask();
        }

        boolean restrictMovement = getConfig().getBoolean("queue.restrict-movement", true);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers(), restrictMovement), this);

        getServer().getPluginManager().registerEvents(new DimensionListener(this, loginWorldManager), this);

        sayLog();

        // WORLD 模式下，性能模式只对登录世界生效
        boolean performanceMode = getConfig().getBoolean("queue.performance-mode", true);
        getServer().getPluginManager().registerEvents(new PerformanceListener(performanceMode, loginWorldManager), this);
        if (performanceMode) {
            if (worldMode && loginWorldManager.getLoginWorld() != null) {
                SchedulerUtil.runTask(this, () -> PerformanceListener.applyWorldSettings(loginWorldManager.getLoginWorld()));
            } else if (!worldMode) {
                for (org.bukkit.World world : getServer().getWorlds()) {
                    final org.bukkit.World w = world;
                    SchedulerUtil.runTask(this, () -> PerformanceListener.applyWorldSettings(w));
                }
            }
        }

        LoginQueue2Command commandExecutor = new LoginQueue2Command(this, playerJoinListener);
        getCommand("logseq").setExecutor(commandExecutor);
        getCommand("logseq").setTabCompleter(commandExecutor);
        getCommand("ls").setExecutor(commandExecutor);
        getCommand("ls").setTabCompleter(commandExecutor);

        getCommand("join").setExecutor(new JoinCommand(this, playerJoinListener));

        AuthCommand authCommand = new AuthCommand(this, authManager, playerJoinListener);
        getCommand("register").setExecutor(authCommand);
        getCommand("login").setExecutor(authCommand);
        getCommand("changepassword").setExecutor(authCommand);
        getCommand("changepw").setExecutor(authCommand);

        // WORLD 模式下禁用 BungeeCord 刷新任务
        if (!worldMode) {
            startRefreshTask();
        }

        // 延迟检查代理端插件版本（给网络连接一点初始化时间）
        if (!worldMode && enableBungeeExtension) {
            SchedulerUtil.runTaskLater(this, () -> {
                if (isEnabled() && messenger != null) {
                    messenger.requestProxyVersion();
                }
            }, 200L); // 10秒后检查
        }

        logStartupConfig();

        // 根据优先级和可用性决定使用哪种认证系统
        String authPriority = getConfig().getString("auth.priority", "AUTHME").toUpperCase();
        boolean useAuthMe = false;
        boolean useBuiltin = false;

        if ("AUTHME".equals(authPriority)) {
            // AuthMe 优先
            getLogger().info(languageManager.getLogMessage("auth-priority-authme"));
            if (authMeCompatManager.isEnabled()) {
                useAuthMe = true;
            } else if (authManager.isEnabled()) {
                useBuiltin = true;
                getLogger().info(languageManager.getLogMessage("auth-fallback-builtin"));
            }
        } else {
            // 内置认证优先
            getLogger().info(languageManager.getLogMessage("auth-priority-builtin"));
            if (authManager.isEnabled()) {
                useBuiltin = true;
            } else if (authMeCompatManager.isEnabled()) {
                useAuthMe = true;
                getLogger().info(languageManager.getLogMessage("auth-fallback-authme"));
            }
        }

        if (useAuthMe) {
            getLogger().info(languageManager.getLogMessage("authme-compat-enabled"));
        } else if (useBuiltin) {
            getLogger().info(languageManager.getLogMessage("auth-enabled"));
        } else {
            getLogger().info(languageManager.getLogMessage("auth-disabled"));
        }
        getLogger().info(languageManager.getLogMessage("plugin-enabled"));
    }

    private void startRefreshTask() {
        long onlineInterval = getConfig().getLong("queue.refresh-interval", 5) * 20L;
        long offlineInterval = getConfig().getLong("queue.offline-refresh-interval", 10) * 20L;

        scheduleNextRefresh(onlineInterval, offlineInterval, 20L);
    }

    /**
     * WORLD 模式下启动主世界未授权玩家监控任务
     */
    private void startMainWorldMonitorTask() {
        long monitorInterval = getConfig().getLong("world-mode.monitor-interval", 5) * 20L;
        if (monitorInterval <= 0) {
            monitorInterval = 100L; // 默认 5 秒
        }
        SchedulerUtil.runTaskTimer(this, () -> {
            if (worldInventoryListener != null) {
                worldInventoryListener.checkUnauthorizedPlayersInMainWorld();
            }
        }, monitorInterval, monitorInterval);
        getLogger().info(languageManager.getLogMessage("main-world-monitor-started", "interval", String.valueOf(monitorInterval / 20L)));
    }

    private void sayLog() {
        // logo
        getLogger().info(ChatColor.AQUA+"    "+" "+ChatColor.BLUE+" __ ");
        getLogger().info(ChatColor.AQUA+"|   "+" "+ChatColor.BLUE+"(__ ");
        getLogger().info(ChatColor.AQUA+"|___"+" "+ChatColor.BLUE+" __)"+" "+ChatColor.YELLOW+"2" );
        getLogger().info("");
    }
    private void logStartupConfig() {
        getLogger().info(languageManager.getLogMessage("config-main-server", "server", getConfig().getString("queue.main-server", "main")));
        getLogger().info(languageManager.getLogMessage("config-max-online", "max", String.valueOf(getConfig().getInt("queue.max-online", 50))));
        getLogger().info(languageManager.getLogMessage("config-threshold", "threshold", String.valueOf(getConfig().getDouble("queue.threshold", 0.8))));
        getLogger().info(languageManager.getLogMessage("config-restrict-movement", "enabled", String.valueOf(getConfig().getBoolean("queue.restrict-movement", true))));
        getLogger().info(languageManager.getLogMessage("config-performance-mode", "enabled", String.valueOf(getConfig().getBoolean("queue.performance-mode", true))));
        getLogger().info(languageManager.getLogMessage("config-restrict-range", "enabled", String.valueOf(getConfig().getBoolean("queue.restrict-range", false)), "range", String.valueOf(getConfig().getDouble("queue.range-limit", 10.0))));
        getLogger().info(languageManager.getLogMessage("config-spawn-protection", "enabled", String.valueOf(getConfig().getBoolean("queue.spawn-protection", true)), "radius", String.valueOf(getConfig().getDouble("queue.spawn-protection-radius", 0.0))));
        getLogger().info(languageManager.getLogMessage("config-spawn-world", "world", getConfig().getString("queue.spawn.world", "world"), "x", String.valueOf(getConfig().getDouble("queue.spawn.x", 0.0)), "y", String.valueOf(getConfig().getDouble("queue.spawn.y", 64.0)), "z", String.valueOf(getConfig().getDouble("queue.spawn.z", 0.0))));
        getLogger().info(languageManager.getLogMessage("config-bungee-extension", "enabled", String.valueOf(getConfig().getBoolean("enable-bungee-extension", true))));
        logServerStatus();
    }

    private void logServerStatus() {
        // WORLD 模式下不显示 BungeeCord 服务器状态
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            return;
        }
        if (!messenger.isEnabled()) {
            return;
        }
        java.util.Map<String, BungeeMessenger.ServerStatus> all = messenger.getAllServerStatus();
        if (all.isEmpty()) {
            return;
        }
        getLogger().info(languageManager.getLogMessage("cached-server-status"));
        for (BungeeMessenger.ServerStatus status : all.values()) {
            getLogger().info(languageManager.getLogMessage("server-status-format",
                    "server", status.getServerName(),
                    "online", String.valueOf(status.getOnlinePlayers()),
                    "max", String.valueOf(status.getMaxPlayers()),
                    "tps", String.format("%.1f", status.getTps()),
                    "usedMemory", String.valueOf(status.getUsedMemory()),
                    "maxMemory", String.valueOf(status.getMaxMemory()),
                    "status", status.isOnline() ? languageManager.getLogMessage("online") : languageManager.getLogMessage("offline")));
        }
    }

    private void scheduleNextRefresh(long onlineInterval, long offlineInterval, long delay) {
        // WORLD 模式下不启动刷新任务
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            return;
        }
        SchedulerUtil.runTaskLater(this, () -> {
            if (!isEnabled()) {
                return;
            }

            messenger.refresh();

            long nextInterval = messenger.isMainServerOnline() ? onlineInterval : offlineInterval;
            scheduleNextRefresh(onlineInterval, offlineInterval, nextInterval);
        }, delay);
    }

    @Override
    public void onDisable() {
        if (scoreboardManager != null) {
            scoreboardManager.shutdown();
        }
        if (messenger != null) {
            messenger.shutdown();
        }
        if (authManager != null) {
            authManager.close();
        }
        getLogger().info(languageManager.getLogMessage("plugin-disabled"));
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return playerJoinListener;
    }

    public BungeeMessenger getMessenger() {
        return messenger;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public AuthRestrictionListener getAuthRestrictionListener() {
        return authRestrictionListener;
    }

    public AuthMeCompatManager getAuthMeCompatManager() {
        return authMeCompatManager;
    }

    /**
     * 判断当前实际使用的认证系统
     * @return "AUTHME" 使用AuthMe, "BUILTIN" 使用内置认证, null 未启用认证
     */
    public String getActiveAuthSystem() {
        String authPriority = getConfig().getString("auth.priority", "AUTHME").toUpperCase();

        if ("AUTHME".equals(authPriority)) {
            // AuthMe 优先
            if (authMeCompatManager.isEnabled()) {
                return "AUTHME";
            } else if (authManager.isEnabled()) {
                return "BUILTIN";
            }
        } else {
            // 内置认证优先
            if (authManager.isEnabled()) {
                return "BUILTIN";
            } else if (authMeCompatManager.isEnabled()) {
                return "AUTHME";
            }
        }
        return null;
    }

    public PlayerRestrictionListener getPlayerRestrictionListener() {
        return playerRestrictionListener;
    }

    public ServerScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public LoginWorldManager getLoginWorldManager() {
        return loginWorldManager;
    }

    public QueueItemListener getQueueItemListener() {
        return queueItemListener;
    }

    public WorldInventoryListener getWorldInventoryListener() {
        return worldInventoryListener;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        getConfig().set("debug", debug);
        saveConfig();
    }
}
