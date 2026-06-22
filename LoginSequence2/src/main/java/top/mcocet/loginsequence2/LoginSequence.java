package top.mcocet.loginsequence2;

import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2.auth.AuthCommand;
import top.mcocet.loginsequence2.auth.AuthManager;
import top.mcocet.loginsequence2.auth.AuthRestrictionListener;
import top.mcocet.loginsequence2.bungee.BungeeMessenger;
import top.mcocet.loginsequence2.command.JoinCommand;
import top.mcocet.loginsequence2.command.LoginSequenceCommand;
import top.mcocet.loginsequence2.listener.DimensionListener;
import top.mcocet.loginsequence2.util.LanguageManager;
import top.mcocet.loginsequence2.listener.PerformanceListener;
import top.mcocet.loginsequence2.listener.PlayerJoinListener;
import top.mcocet.loginsequence2.listener.PlayerMoveListener;
import top.mcocet.loginsequence2.listener.PlayerRestrictionListener;
import top.mcocet.loginsequence2.listener.QueueItemListener;

public final class LoginSequence extends JavaPlugin {

    private BungeeMessenger messenger;
    private PlayerJoinListener playerJoinListener;
    private LanguageManager languageManager;
    private AuthManager authManager;
    private AuthRestrictionListener authRestrictionListener;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.debug = getConfig().getBoolean("debug", false);
        this.languageManager = new LanguageManager(this);

        boolean enableBungeeExtension = getConfig().getBoolean("enable-bungee-extension", true);
        this.messenger = new BungeeMessenger(this, enableBungeeExtension);
        getLogger().info("BungeeCord 通道扩展: " + (enableBungeeExtension ? "已启用" : "已禁用") + "。");
        this.authManager = new AuthManager(this);
        this.authRestrictionListener = new AuthRestrictionListener(this, authManager);
        getServer().getPluginManager().registerEvents(authRestrictionListener, this);

        this.playerJoinListener = new PlayerJoinListener(this, messenger, authManager, authRestrictionListener);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers()), this);

        getServer().getPluginManager().registerEvents(new QueueItemListener(this, playerJoinListener), this);

        boolean restrictMovement = getConfig().getBoolean("queue.restrict-movement", true);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers(), restrictMovement), this);

        getServer().getPluginManager().registerEvents(new DimensionListener(this), this);

        sayLog();

        boolean performanceMode = getConfig().getBoolean("queue.performance-mode", true);
        getServer().getPluginManager().registerEvents(new PerformanceListener(performanceMode), this);
        if (performanceMode) {
            for (org.bukkit.World world : getServer().getWorlds()) {
                PerformanceListener.applyWorldSettings(world);
            }
        }

        LoginSequenceCommand commandExecutor = new LoginSequenceCommand(this, playerJoinListener);
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

        startRefreshTask();

        logStartupConfig();

        if (authManager.isEnabled()) {
            getLogger().info("认证系统已启用。");
        } else {
            getLogger().info("认证系统已禁用。");
        }
        getLogger().info("LoginSequence 已启用，登录队列系统已加载。");
    }

    private void startRefreshTask() {
        long onlineInterval = getConfig().getLong("queue.refresh-interval", 5) * 20L;
        long offlineInterval = getConfig().getLong("queue.offline-refresh-interval", 10) * 20L;

        scheduleNextRefresh(onlineInterval, offlineInterval, 20L);
    }

    private void sayLog() {
        // logo
        getLogger().info(ChatColor.AQUA+"    "+" "+ChatColor.BLUE+" __ ");
        getLogger().info(ChatColor.AQUA+"|   "+" "+ChatColor.BLUE+"(__ ");
        getLogger().info(ChatColor.AQUA+"|___"+" "+ChatColor.BLUE+" __)"+" "+ChatColor.YELLOW+"2" );
        getLogger().info("");
    }
    private void logStartupConfig() {
        getLogger().info("主服务器: " + getConfig().getString("queue.main-server", "main"));
        getLogger().info("最大在线: " + getConfig().getInt("queue.max-online", 50));
        getLogger().info("连接阈值: " + getConfig().getDouble("queue.threshold", 0.8));
        getLogger().info("限制移动: " + getConfig().getBoolean("queue.restrict-movement", true));
        getLogger().info("性能节省模式: " + getConfig().getBoolean("queue.performance-mode", true));
        getLogger().info("限制活动范围: " + getConfig().getBoolean("queue.restrict-range", false)
                + " (范围: " + getConfig().getDouble("queue.range-limit", 10.0) + " 方块)");
        getLogger().info("登录点保护: " + getConfig().getBoolean("queue.spawn-protection", true)
                + " (半径: " + getConfig().getDouble("queue.spawn-protection-radius", 0.0) + " 方块)");
        getLogger().info("出生点世界: " + getConfig().getString("queue.spawn.world", "world")
                + " (" + getConfig().getDouble("queue.spawn.x", 0.0)
                + ", " + getConfig().getDouble("queue.spawn.y", 64.0)
                + ", " + getConfig().getDouble("queue.spawn.z", 0.0) + ")");
        getLogger().info("BungeeCord 通道扩展: " + getConfig().getBoolean("enable-bungee-extension", true));
        logServerStatus();
    }

    private void logServerStatus() {
        if (!messenger.isEnabled()) {
            return;
        }
        java.util.Map<String, BungeeMessenger.ServerStatus> all = messenger.getAllServerStatus();
        if (all.isEmpty()) {
            return;
        }
        getLogger().info("当前已缓存子服务器状态:");
        for (BungeeMessenger.ServerStatus status : all.values()) {
            getLogger().info("  " + status);
        }
    }

    private void scheduleNextRefresh(long onlineInterval, long offlineInterval, long delay) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    return;
                }

                messenger.refresh();

                long nextInterval = messenger.isMainServerOnline() ? onlineInterval : offlineInterval;
                scheduleNextRefresh(onlineInterval, offlineInterval, nextInterval);
            }
        }.runTaskLater(this, delay);
    }

    @Override
    public void onDisable() {
        if (messenger != null) {
            messenger.shutdown();
        }
        if (authManager != null) {
            authManager.close();
        }
        getLogger().info("LoginSequence 已禁用。");
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

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        getConfig().set("debug", debug);
        saveConfig();
    }
}
