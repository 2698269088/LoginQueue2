package top.mcocet.loginqueue2limbo.command;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.commands.TabCompletor;
import com.loohp.limbo.player.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.auth.AuthDataMigrator;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.listener.PlayerJoinListener;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LoginQueue2LimboCommand implements CommandExecutor, TabCompletor {

    private final LoginQueue2Limbo plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public LoginQueue2LimboCommand(LoginQueue2Limbo plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return;
        }

        String cmd = args[0].toLowerCase();
        if (!cmd.equals("logseq") && !cmd.equals("ls")) {
            return;
        }

        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        if (subArgs.length == 0) {
            sendHelp(sender);
            return;
        }

        String sub = subArgs[0].toLowerCase();
        switch (sub) {
            case "skip":
                handleSkip(sender, subArgs);
                break;
            case "promote":
                handlePromote(sender, subArgs);
                break;
            case "debug":
                handleDebug(sender);
                break;
            case "list":
                handleList(sender);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "refresh":
                handleRefresh(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "pause":
                handlePause(sender);
                break;
            case "resume":
                handleResume(sender);
                break;
            case "info":
                handleInfo(sender);
                break;
            case "migrate":
                handleMigrate(sender, subArgs);
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                sender.sendMessage(languageManager.getMessage("unknown-subcommand"));
                break;
        }
    }

    private void handleSkip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginqueue2.admin.skip")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = Limbo.getInstance().getPlayer(args[1]);
            if (target == null || !target.isValid()) {
                sender.sendMessage(languageManager.getMessage("player-offline", "player", args[1]));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(languageManager.getMessage("console-specify-player"));
            return;
        }

        listener.allowPlayerDirectly(target);
        sender.sendMessage(languageManager.getMessage("skipped-player", "player", target.getName()));
    }

    private void handlePromote(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginqueue2.admin.promote")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        Player target;
        if (args.length >= 2) {
            target = Limbo.getInstance().getPlayer(args[1]);
            if (target == null || !target.isValid()) {
                sender.sendMessage(languageManager.getMessage("player-offline", "player", args[1]));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(languageManager.getMessage("console-specify-player"));
            return;
        }

        if (listener.promotePlayerInQueue(target)) {
            sender.sendMessage(languageManager.getMessage("queue-promote-success", "player", target.getName()));
            target.sendMessage(languageManager.getMessage("queue-position-advanced"));
        } else {
            sender.sendMessage(languageManager.getMessage("queue-promote-fail", "player", target.getName()));
        }
    }

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.list")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        if (listener.isPerServerQueueMode()) {
            Map<String, Integer> serverQueueSizes = listener.getServerQueueSizes();
            sender.sendMessage(languageManager.getMessage("queue-list-header"));
            if (serverQueueSizes.isEmpty()) {
                sender.sendMessage(languageManager.getMessage("queue-list-empty"));
            } else {
                for (Map.Entry<String, Integer> entry : serverQueueSizes.entrySet()) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&e" + entry.getKey() + ": " + entry.getValue() + " 人排队")));
                }
                List<String> queueList = listener.getQueuePlayerNames();
                for (int i = 0; i < queueList.size(); i++) {
                    sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&e" + (i + 1) + ". " + queueList.get(i))));
                }
            }
            sender.sendMessage(languageManager.getMessage("queue-list-footer"));
            return;
        }

        List<String> queueList = listener.getQueuePlayerNames();
        sender.sendMessage(languageManager.getMessage("queue-list-header"));
        if (queueList.isEmpty()) {
            sender.sendMessage(languageManager.getMessage("queue-list-empty"));
        } else {
            for (int i = 0; i < queueList.size(); i++) {
                sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&e" + (i + 1) + ". " + queueList.get(i))));
            }
        }
        sender.sendMessage(languageManager.getMessage("queue-list-footer"));
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.status")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        double threshold = plugin.getConfigValueDouble("queue.threshold", 0.8);
        String balanceStrategy = plugin.getConfigValueString("queue.balance-strategy", "LEAST_PLAYERS");

        sender.sendMessage(languageManager.getMessage("status-header"));

        java.util.Map<String, BungeeMessenger.ServerStatus> allStatus = messenger.getAllServerStatus();
        if (allStatus.isEmpty()) {
            sender.sendMessage(languageManager.getMessage("status-offline"));
        } else {
            int totalOnline = 0;
            int totalMax = 0;
            for (BungeeMessenger.ServerStatus status : allStatus.values()) {
                boolean isOnline = messenger.isServerOnline(status.getServerName());
                sender.sendMessage(languageManager.getMessage("status-main-server", "server", status.getServerName()));
                sender.sendMessage(languageManager.getMessage(isOnline ? "status-online" : "status-offline"));
                if (isOnline) {
                    double ratio = status.getMaxPlayers() > 0 ? status.getLoadRatio() : 0;
                    sender.sendMessage(languageManager.getMessage("status-players",
                            "online", String.valueOf(status.getOnlinePlayers()),
                            "max", String.valueOf(status.getMaxPlayers())));
                    sender.sendMessage(languageManager.getMessage("status-ratio", "ratio", String.format("%.1f", ratio * 100)));
                    totalOnline += status.getOnlinePlayers();
                    totalMax += status.getMaxPlayers();
                }
            }
            if (totalMax > 0) {
                double totalRatio = (double) totalOnline / totalMax;
                sender.sendMessage(languageManager.getMessage("total-online", "online", String.valueOf(totalOnline), "max", String.valueOf(totalMax), "ratio", String.format("%.1f", totalRatio * 100)));
                sender.sendMessage(languageManager.getMessage("status-threshold", "threshold", String.format("%.1f", threshold * 100)));
                sender.sendMessage(languageManager.getMessage(totalRatio >= threshold ? "status-queue-paused" : "status-queue-normal"));
            }
        }
        sender.sendMessage(languageManager.getMessage("balance-strategy", "strategy", balanceStrategy));
        if (listener.isPerServerQueueMode()) {
            Map<String, Integer> serverQueueSizes = listener.getServerQueueSizes();
            for (Map.Entry<String, Integer> entry : serverQueueSizes.entrySet()) {
                sender.sendMessage(languageManager.getMessage("status-server-queue-size", "server", entry.getKey(), "size", String.valueOf(entry.getValue())));
            }
        } else {
            sender.sendMessage(languageManager.getMessage("status-queue-size", "size", String.valueOf(listener.getQueueSize())));
        }
        sender.sendMessage(languageManager.getMessage("status-footer"));
    }

    private void handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.refresh")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        plugin.getMessenger().refresh();
        sender.sendMessage(languageManager.getMessage("refreshed"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.reload")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        languageManager.reload();
        listener.reloadPriority();
        sender.sendMessage(languageManager.getMessage("reloaded"));
    }

    private void handlePause(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.pause")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        if (listener.isQueuePaused()) {
            sender.sendMessage(languageManager.getMessage("queue-already-paused"));
            return;
        }

        listener.pauseQueue();
        sender.sendMessage(languageManager.getMessage("queue-paused"));
    }

    private void handleResume(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.pause")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        if (!listener.isQueuePaused()) {
            sender.sendMessage(languageManager.getMessage("queue-already-running"));
            return;
        }

        listener.resumeQueue();
        sender.sendMessage(languageManager.getMessage("queue-resumed-admin"));
    }

    private void handleDebug(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.debug")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        boolean newState = !plugin.isDebug();
        plugin.setDebug(newState);
        sender.sendMessage(newState ? languageManager.getMessage("debug-mode-on") : languageManager.getMessage("debug-mode-off"));
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.info")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        if (!messenger.isEnabled()) {
            sender.sendMessage(languageManager.getMessage("bungee-extension-disabled"));
            return;
        }

        java.util.Map<String, BungeeMessenger.ServerStatus> allStatus = messenger.getAllServerStatus();
        if (allStatus.isEmpty()) {
            sender.sendMessage(languageManager.getMessage("main-server-no-data"));
            return;
        }

        // 当 UDP 启用且优先时，只显示 UDP 配置的服务器信息
        boolean udpEnabled = plugin.getConfigValueBoolean("udp-sync.enabled", false);
        String udpPriority = plugin.getConfigValueString("udp-sync.priority", "BC_CHANNEL");
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);

        java.util.List<String> udpServerNames = new java.util.ArrayList<>();
        if (udpPreferred) {
            for (java.util.Map<?, ?> map : plugin.getConfigValueMapList("udp-sync.servers")) {
                Object nameObj = map.get("name");
                if (nameObj != null) {
                    udpServerNames.add(String.valueOf(nameObj));
                }
            }
            // 兼容旧配置（单服务器模式）
            if (udpServerNames.isEmpty() && plugin.getConfigValueString("udp-sync.host", null) != null) {
                udpServerNames.add(plugin.getConfigValueString("queue.main-server", "main"));
            }
        }

        boolean anyDisplayed = false;
        for (BungeeMessenger.ServerStatus status : allStatus.values()) {
            // UDP 优先模式下，过滤只显示 UDP 配置的服务器
            if (udpPreferred && !udpServerNames.contains(status.getServerName())) {
                continue;
            }
            anyDisplayed = true;
            sender.sendMessage(languageManager.getMessage("info-header", "server", status.getServerName()));
            sender.sendMessage(languageManager.getMessage("info-status",
                    "status", status.isOnline() ? languageManager.getMessage("online") : languageManager.getMessage("offline")));
            sender.sendMessage(languageManager.getMessage("info-players",
                    "online", String.valueOf(status.getOnlinePlayers()),
                    "max", String.valueOf(status.getMaxPlayers())));
            sender.sendMessage(languageManager.getMessage("info-load",
                    "ratio", String.format("%.1f", status.getLoadRatio() * 100)));
        }

        if (!anyDisplayed) {
            sender.sendMessage(languageManager.getMessage("main-server-no-data"));
            return;
        }
        sender.sendMessage(languageManager.getMessage("info-footer"));
    }

    private void handleMigrate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginqueue2.admin.migrate")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("&e用法: /logseq migrate <from> <to>");
            sender.sendMessage("&e  支持: file, mysql, sqlite");
            sender.sendMessage("&e  示例: /logseq migrate file mysql");
            sender.sendMessage("&e  示例: /logseq migrate mysql file");
            sender.sendMessage("&e  示例: /logseq migrate sqlite mysql");
            return;
        }

        String from = args[1].toLowerCase();
        String to = args[2].toLowerCase();

        java.util.List<String> validTypes = Arrays.asList("file", "mysql", "sqlite");
        if (!validTypes.contains(from)) {
            sender.sendMessage("&c无效的源: " + from);
            sender.sendMessage("&e支持: file, mysql, sqlite");
            return;
        }
        if (!validTypes.contains(to)) {
            sender.sendMessage("&c无效的目标: " + to);
            sender.sendMessage("&e支持: file, mysql, sqlite");
            return;
        }
        if (from.equals(to)) {
            sender.sendMessage("&c源和目标不能相同");
            return;
        }

        AuthDataMigrator migrator = new AuthDataMigrator(plugin);
        migrator.migrateAsync(sender, from, to, result -> {
            if (result.success) {
                sender.sendMessage(languageManager.getMessage("migrate-success", "message", result.message));
            } else {
                sender.sendMessage(languageManager.getMessage("migrate-fail", "error", result.message));
            }
        });

        sender.sendMessage("&a[迁移] 迁移任务已在后台启动，请查看控制台输出进度...");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(languageManager.getMessage("help-header"));
        sender.sendMessage(languageManager.getMessage("help-skip"));
        sender.sendMessage(languageManager.getMessage("help-promote"));
        sender.sendMessage(languageManager.getMessage("help-debug"));
        sender.sendMessage(languageManager.getMessage("help-list"));
        sender.sendMessage(languageManager.getMessage("help-status"));
        sender.sendMessage(languageManager.getMessage("help-refresh"));
        sender.sendMessage(languageManager.getMessage("help-reload"));
        sender.sendMessage(languageManager.getMessage("help-pause"));
        sender.sendMessage(languageManager.getMessage("help-resume"));
        sender.sendMessage(languageManager.getMessage("help-info"));
        sender.sendMessage(languageManager.getMessage("help-migrate"));
        sender.sendMessage(languageManager.getMessage("help-help"));
        sender.sendMessage(languageManager.getMessage("help-footer"));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }

        String cmd = args[0].toLowerCase();
        if (!cmd.equals("logseq") && !cmd.equals("ls")) {
            return Collections.emptyList();
        }

        String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];

        if (subArgs.length == 0) {
            List<String> subs = Arrays.asList("skip", "promote", "debug", "list", "status", "refresh", "reload", "info", "migrate", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                result.add(sub);
            }
            return result;
        }
        if (subArgs.length == 1) {
            List<String> subs = Arrays.asList("skip", "promote", "debug", "list", "status", "refresh", "reload", "pause", "resume", "info", "migrate", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(subArgs[0].toLowerCase())) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (subArgs.length == 2) {
            if ("skip".equalsIgnoreCase(subArgs[0]) || "promote".equalsIgnoreCase(subArgs[0])) {
                String perm = "skip".equalsIgnoreCase(subArgs[0]) ? "loginqueue2.admin.skip" : "loginqueue2.admin.promote";
                if (sender.hasPermission(perm)) {
                    List<String> names = new ArrayList<>();
                    for (Player player : Limbo.getInstance().getPlayers()) {
                        if (player.getName().toLowerCase().startsWith(subArgs[1].toLowerCase())) {
                            names.add(player.getName());
                        }
                    }
                    return names;
                }
            }
            if ("migrate".equalsIgnoreCase(subArgs[0])) {
                return Arrays.asList("file", "sqlite", "mysql");
            }
        }
        if (subArgs.length == 3 && "migrate".equalsIgnoreCase(subArgs[0])) {
            java.util.List<String> options = Arrays.asList("file", "sqlite", "mysql");
            java.util.List<String> result = new ArrayList<>();
            for (String opt : options) {
                if (opt.startsWith(subArgs[2].toLowerCase()) && !opt.equalsIgnoreCase(subArgs[1])) {
                    result.add(opt);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
