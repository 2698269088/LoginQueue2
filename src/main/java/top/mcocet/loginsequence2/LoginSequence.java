package top.mcocet.loginsequence2;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import top.mcocet.loginsequence2.bungee.BungeeMessenger;
import top.mcocet.loginsequence2.command.JoinCommand;
import top.mcocet.loginsequence2.command.LoginSequenceCommand;
import top.mcocet.loginsequence2.listener.PerformanceListener;
import top.mcocet.loginsequence2.listener.PlayerJoinListener;
import top.mcocet.loginsequence2.listener.PlayerRestrictionListener;
import top.mcocet.loginsequence2.listener.QueueItemListener;

public final class LoginSequence extends JavaPlugin {

    private BungeeMessenger messenger;
    private PlayerJoinListener playerJoinListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messenger = new BungeeMessenger(this);
        this.playerJoinListener = new PlayerJoinListener(this, messenger);
        getServer().getPluginManager().registerEvents(playerJoinListener, this);

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers()), this);

        getServer().getPluginManager().registerEvents(new QueueItemListener(this, playerJoinListener), this);

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

        startRefreshTask();

        getLogger().info("LoginSequence 已启用，登录队列系统已加载。");
    }

    private void startRefreshTask() {
        long onlineInterval = getConfig().getLong("queue.refresh-interval", 5) * 20L;
        long offlineInterval = getConfig().getLong("queue.offline-refresh-interval", 10) * 20L;

        scheduleNextRefresh(onlineInterval, offlineInterval, 20L);
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
        getLogger().info("LoginSequence 已禁用。");
    }

    public BungeeMessenger getMessenger() {
        return messenger;
    }
}
