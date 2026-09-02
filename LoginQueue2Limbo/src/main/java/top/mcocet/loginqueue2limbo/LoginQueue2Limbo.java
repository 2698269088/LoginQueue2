package top.mcocet.loginqueue2limbo;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.player.PluginMessageEvent;
import com.loohp.limbo.file.FileConfiguration;
import com.loohp.limbo.plugins.LimboPlugin;
import com.loohp.limbo.scheduler.LimboTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginqueue2limbo.auth.AuthCommand;
import top.mcocet.loginqueue2limbo.auth.AuthManager;
import top.mcocet.loginqueue2limbo.auth.AuthRestrictionListener;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.command.JoinCommand;
import top.mcocet.loginqueue2limbo.command.LoginQueue2LimboCommand;
import top.mcocet.loginqueue2limbo.gui.ServerSelectorMenu;
import top.mcocet.loginqueue2limbo.listener.PlayerJoinListener;
import top.mcocet.loginqueue2limbo.listener.PlayerMoveListener;
import top.mcocet.loginqueue2limbo.listener.PlayerRestrictionListener;
import top.mcocet.loginqueue2limbo.listener.PluginMessageListener;
import top.mcocet.loginqueue2limbo.listener.QueueItemListener;
import top.mcocet.loginqueue2limbo.util.LanguageManager;
import top.mcocet.loginqueue2limbo.scoreboard.ServerScoreboardManager;
import top.mcocet.loginqueue2limbo.udp.UDPServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

public class LoginQueue2Limbo extends LimboPlugin {

    private BungeeMessenger messenger;
    private PlayerJoinListener playerJoinListener;
    private LanguageManager languageManager;
    private AuthManager authManager;
    private AuthRestrictionListener authRestrictionListener;
    private ServerScoreboardManager scoreboardManager;
    private ServerSelectorMenu serverSelectorMenu;
    private UDPServer udpServer;
    private FileConfiguration config;
    private boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.debug = getConfig().get("debug", Boolean.class) != null ? getConfig().get("debug", Boolean.class) : false;
        this.languageManager = new LanguageManager(this);

        boolean enableBungeeExtension = getConfig().get("enable-bungee-extension", Boolean.class) != null ? getConfig().get("enable-bungee-extension", Boolean.class) : true;
        this.messenger = new BungeeMessenger(this, enableBungeeExtension);
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("bungee-extension-status", "status", languageManager.getLogMessage(enableBungeeExtension ? "enabled" : "disabled")));

        this.authManager = new AuthManager(this);
        this.authRestrictionListener = new AuthRestrictionListener(this, authManager);
        Limbo.getInstance().getEventsManager().registerEvents(this, authRestrictionListener);

        this.playerJoinListener = new PlayerJoinListener(this, messenger, authManager, authRestrictionListener);
        Limbo.getInstance().getEventsManager().registerEvents(this, playerJoinListener);
        Limbo.getInstance().getEventsManager().registerEvents(this, new PlayerRestrictionListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers()));

        boolean restrictMovement = getConfigValueBoolean("queue.restrict-movement", true);
        Limbo.getInstance().getEventsManager().registerEvents(this, new PlayerMoveListener(this, playerJoinListener, playerJoinListener.getAllowedPlayers(), restrictMovement));

        Limbo.getInstance().getEventsManager().registerEvents(this, new QueueItemListener(this, playerJoinListener));

        Limbo.getInstance().getEventsManager().registerEvents(this, new PluginMessageListener(messenger));

        this.serverSelectorMenu = new ServerSelectorMenu(this);
        Limbo.getInstance().getEventsManager().registerEvents(this, serverSelectorMenu);

        // 启动 UDP 服务端，接收子服务器的 /connect 虚拟排队请求
        boolean udpEnabled = getConfigValueBoolean("udp-sync.enabled", false);
        boolean connectQueueEnabled = getConfigValueBoolean("udp-sync.connect-queue.enabled", false);
        if (udpEnabled && connectQueueEnabled) {
            int serverPort = getConfigValueInt("udp-sync.connect-queue.server-port", 16648);
            this.udpServer = new UDPServer(this, messenger, playerJoinListener, serverPort);
            this.udpServer.start();
        }

        this.scoreboardManager = new ServerScoreboardManager(this, messenger);

        sayLog();

        LoginQueue2LimboCommand commandExecutor = new LoginQueue2LimboCommand(this, playerJoinListener);
        Limbo.getInstance().getPluginManager().registerCommands(this, commandExecutor);

        JoinCommand joinCommand = new JoinCommand(this, playerJoinListener);
        Limbo.getInstance().getPluginManager().registerCommands(this, joinCommand);

        AuthCommand authCommand = new AuthCommand(this, authManager, playerJoinListener);
        Limbo.getInstance().getPluginManager().registerCommands(this, authCommand);

        startRefreshTask();

        logStartupConfig();

        if (authManager.isEnabled()) {
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-enabled"));
        } else {
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-disabled"));
        }
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-enabled"));
    }

    private void startRefreshTask() {
        long onlineInterval = getConfigValueLong("queue.refresh-interval", 5) * 20L;
        long offlineInterval = getConfigValueLong("queue.offline-refresh-interval", 10) * 20L;
        scheduleNextRefresh(onlineInterval, offlineInterval, 20L);
    }

    private void scheduleNextRefresh(long onlineInterval, long offlineInterval, long delay) {
        Limbo.getInstance().getScheduler().runTaskLater(this, new LimboTask() {
            @Override
            public void run() {
                if (!Limbo.getInstance().isRunning()) {
                    return;
                }
                messenger.refresh();
                long nextInterval = messenger.isMainServerOnline() ? onlineInterval : offlineInterval;
                scheduleNextRefresh(onlineInterval, offlineInterval, nextInterval);
            }
        }, delay);
    }

    private void sayLog() {
        Limbo.getInstance().getConsole().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§b    §1 __ "));
        Limbo.getInstance().getConsole().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§b|   §1(__ "));
        Limbo.getInstance().getConsole().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§b|___§1 __)§e 2 Limbo"));
        Limbo.getInstance().getConsole().sendMessage(Component.empty());
    }

    private void logStartupConfig() {
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-main-server", "server", getConfigValueString("queue.main-server", "main")));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-max-online", "max", String.valueOf(getConfigValueInt("queue.max-online", 50))));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-threshold", "threshold", String.valueOf(getConfigValueDouble("queue.threshold", 0.8))));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-restrict-movement", "enabled", String.valueOf(getConfigValueBoolean("queue.restrict-movement", true))));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-spawn-protection", "enabled", String.valueOf(getConfigValueBoolean("queue.spawn-protection", true)), "radius", String.valueOf(getConfigValueDouble("queue.spawn-protection-radius", 0.0))));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-spawn-world", "world", getConfigValueString("queue.spawn.world", "world"), "x", String.valueOf(getConfigValueDouble("queue.spawn.x", 0.0)), "y", String.valueOf(getConfigValueDouble("queue.spawn.y", 64.0)), "z", String.valueOf(getConfigValueDouble("queue.spawn.z", 0.0))));
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("config-bungee-extension", "enabled", String.valueOf(getConfigValueBoolean("enable-bungee-extension", true))));
        logServerStatus();
    }

    private void logServerStatus() {
        if (!messenger.isEnabled()) {
            return;
        }
        Map<String, BungeeMessenger.ServerStatus> all = messenger.getAllServerStatus();
        if (all.isEmpty()) {
            return;
        }
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("cached-server-status"));
        for (BungeeMessenger.ServerStatus status : all.values()) {
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + status);
        }
    }

    @Override
    public void onDisable() {
        if (scoreboardManager != null) {
            scoreboardManager.shutdown();
        }
        if (udpServer != null) {
            udpServer.stop();
        }
        if (messenger != null) {
            messenger.shutdown();
        }
        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-disabled"));
    }

    public PlayerJoinListener getPlayerJoinListener() {
        return playerJoinListener;
    }

    public BungeeMessenger getMessenger() {
        return messenger;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public AuthRestrictionListener getAuthRestrictionListener() {
        return authRestrictionListener;
    }

    public ServerScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public ServerSelectorMenu getServerSelectorMenu() {
        return serverSelectorMenu;
    }

    public UDPServer getUDPServer() {
        return udpServer;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
        config.set("debug", debug);
        saveConfig();
    }

    public void reloadConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        try {
            config = new FileConfiguration(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveConfig() {
        try {
            config.saveConfig(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        String[] langFiles = {"zh_CN.yml", "en_US.yml", "zh_TW.yml"};
        for (String langFile : langFiles) {
            File file = new File(langFolder, langFile);
            if (!file.exists()) {
                saveResource("lang/" + langFile, false);
            }
        }
    }

    private void saveResource(String resourcePath, boolean replace) {
        File outFile = new File(getDataFolder(), resourcePath);
        if (outFile.exists() && !replace) {
            return;
        }
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.copy(in, outFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Helper methods for config access
    public String getConfigValueString(String key, String defaultValue) {
        String value = config.get(key, String.class);
        return value != null ? value : defaultValue;
    }

    public int getConfigValueInt(String key, int defaultValue) {
        Object value = config.get(key, Object.class);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    public long getConfigValueLong(String key, long defaultValue) {
        Object value = config.get(key, Object.class);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    public double getConfigValueDouble(String key, double defaultValue) {
        Object value = config.get(key, Object.class);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    public boolean getConfigValueBoolean(String key, boolean defaultValue) {
        Object value = config.get(key, Object.class);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<String> getConfigValueStringList(String key) {
        Object value = config.get(key, Object.class);
        if (value instanceof java.util.List) {
            return (java.util.List<String>) value;
        }
        return new java.util.ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<?, ?>> getConfigValueMapList(String key) {
        Object value = config.get(key, Object.class);
        if (value instanceof java.util.List) {
            return (java.util.List<Map<?, ?>>) value;
        }
        return new java.util.ArrayList<>();
    }
}
