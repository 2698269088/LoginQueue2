package top.mcocet.loginsequence2limbo.command;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.commands.TabCompletor;
import com.loohp.limbo.player.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;
import top.mcocet.loginsequence2limbo.bungee.BungeeMessenger;
import top.mcocet.loginsequence2limbo.listener.PlayerJoinListener;
import top.mcocet.loginsequence2limbo.util.LanguageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoginSequenceCommand implements CommandExecutor, TabCompletor {

    private final LoginSequence2Limbo plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public LoginSequenceCommand(LoginSequence2Limbo plugin, PlayerJoinListener listener) {
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
            case "info":
                handleInfo(sender);
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
        if (!sender.hasPermission("loginsequence.admin.skip")) {
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

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.list")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
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
        if (!sender.hasPermission("loginsequence.admin.status")) {
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
                sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&6总在线: " + totalOnline + "/" + totalMax
                        + " (" + String.format("%.1f", totalRatio * 100) + "%)")));
                sender.sendMessage(languageManager.getMessage("status-threshold", "threshold", String.format("%.1f", threshold * 100)));
                sender.sendMessage(languageManager.getMessage(totalRatio >= threshold ? "status-queue-paused" : "status-queue-normal"));
            }
        }
        sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&b负载均衡策略: " + balanceStrategy)));
        sender.sendMessage(languageManager.getMessage("status-queue-size", "size", String.valueOf(listener.getQueueSize())));
        sender.sendMessage(languageManager.getMessage("status-footer"));
    }

    private void handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.refresh")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        plugin.getMessenger().refresh();
        sender.sendMessage(languageManager.getMessage("refreshed"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.reload")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        languageManager.reload();
        sender.sendMessage(languageManager.getMessage("reloaded"));
    }

    private void handleDebug(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.debug")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return;
        }

        boolean newState = !plugin.isDebug();
        plugin.setDebug(newState);
        sender.sendMessage(LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize("&a[LoginSequence2Limbo] 调试模式已" + (newState ? "开启" : "关闭"))));
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.info")) {
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(languageManager.getMessage("help-header"));
        sender.sendMessage(languageManager.getMessage("help-skip"));
        sender.sendMessage(languageManager.getMessage("help-debug"));
        sender.sendMessage(languageManager.getMessage("help-list"));
        sender.sendMessage(languageManager.getMessage("help-status"));
        sender.sendMessage(languageManager.getMessage("help-refresh"));
        sender.sendMessage(languageManager.getMessage("help-reload"));
        sender.sendMessage(languageManager.getMessage("help-info"));
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
            List<String> subs = Arrays.asList("skip", "debug", "list", "status", "refresh", "reload", "info", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                result.add(sub);
            }
            return result;
        }
        if (subArgs.length == 1) {
            List<String> subs = Arrays.asList("skip", "debug", "list", "status", "refresh", "reload", "info", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(subArgs[0].toLowerCase())) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (subArgs.length == 2 && "skip".equalsIgnoreCase(subArgs[0]) && sender.hasPermission("loginsequence.admin.skip")) {
            List<String> names = new ArrayList<>();
            for (Player player : Limbo.getInstance().getPlayers()) {
                if (player.getName().toLowerCase().startsWith(subArgs[1].toLowerCase())) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }
}
