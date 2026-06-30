package top.mcocet.loginqueue2.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.util.LanguageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoginQueue2Command implements CommandExecutor, TabCompleter {

    private final LoginQueue2 plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public LoginQueue2Command(LoginQueue2 plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "skip":
                return handleSkip(sender, args);
            case "promote":
                return handlePromote(sender, args);
            case "debug":
                return handleDebug(sender);
            case "list":
                return handleList(sender);
            case "status":
                return handleStatus(sender);
            case "refresh":
                return handleRefresh(sender);
            case "reload":
                return handleReload(sender);
            case "info":
                return handleInfo(sender);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage(languageManager.getMessage("unknown-subcommand"));
                return true;
        }
    }

    private boolean handleSkip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginqueue2.admin.skip")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(languageManager.getMessage("player-offline", "player", args[1]));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(languageManager.getMessage("console-specify-player"));
            return true;
        }

        listener.allowPlayerDirectly(target);
        sender.sendMessage(languageManager.getMessage("skipped-player", "player", target.getName()));
        return true;
    }

    private boolean handlePromote(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginqueue2.admin.promote")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(languageManager.getMessage("player-offline", "player", args[1]));
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(languageManager.getMessage("console-specify-player"));
            return true;
        }

        if (listener.promotePlayerInQueue(target)) {
            sender.sendMessage(ChatColor.GREEN + "已将玩家 " + target.getName() + " 的排队位置前进一位");
            target.sendMessage(ChatColor.GREEN + "你的排队位置已前进一位！");
        } else {
            sender.sendMessage(ChatColor.RED + "玩家 " + target.getName() + " 不在排队队列中，或已在第一位");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.list")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        List<String> queueList = listener.getQueuePlayerNames();
        sender.sendMessage(languageManager.getMessage("queue-list-header"));
        if (queueList.isEmpty()) {
            sender.sendMessage(languageManager.getMessage("queue-list-empty"));
        } else {
            for (int i = 0; i < queueList.size(); i++) {
                sender.sendMessage(ChatColor.YELLOW + String.valueOf(i + 1) + ". " + queueList.get(i));
            }
        }
        sender.sendMessage(languageManager.getMessage("queue-list-footer"));
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.status")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        double threshold = plugin.getConfig().getDouble("queue.threshold", 0.8);
        String balanceStrategy = plugin.getConfig().getString("queue.balance-strategy", "LEAST_PLAYERS");

        sender.sendMessage(languageManager.getMessage("status-header"));

        // 显示所有主服务器状态
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
                sender.sendMessage(ChatColor.GOLD + "总在线: " + totalOnline + "/" + totalMax
                        + " (" + String.format("%.1f", totalRatio * 100) + "%)");
                sender.sendMessage(languageManager.getMessage("status-threshold", "threshold", String.format("%.1f", threshold * 100)));
                sender.sendMessage(languageManager.getMessage(totalRatio >= threshold ? "status-queue-paused" : "status-queue-normal"));
            }
        }
        sender.sendMessage(ChatColor.AQUA + "负载均衡策略: " + balanceStrategy);
        sender.sendMessage(languageManager.getMessage("status-queue-size", "size", String.valueOf(listener.getQueueSize())));
        sender.sendMessage(languageManager.getMessage("status-footer"));
        return true;
    }

    private boolean handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.refresh")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        plugin.getMessenger().refresh();
        sender.sendMessage(languageManager.getMessage("refreshed"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.reload")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        languageManager.reload();
        sender.sendMessage(languageManager.getMessage("reloaded"));
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.debug")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        boolean newState = !plugin.isDebug();
        plugin.setDebug(newState);
        sender.sendMessage(ChatColor.GREEN + "[LoginQueue] 调试模式已" + (newState ? "开启" : "关闭"));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("loginqueue2.admin.info")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        if (!messenger.isEnabled()) {
            sender.sendMessage(languageManager.getMessage("bungee-extension-disabled"));
            return true;
        }

        java.util.Map<String, BungeeMessenger.ServerStatus> allStatus = messenger.getAllServerStatus();
        if (allStatus.isEmpty()) {
            sender.sendMessage(languageManager.getMessage("main-server-no-data"));
            return true;
        }

        // 当 UDP 启用且优先时，只显示 UDP 配置的服务器信息
        boolean udpEnabled = plugin.getConfig().getBoolean("udp-sync.enabled", false);
        String udpPriority = plugin.getConfig().getString("udp-sync.priority", "BC_CHANNEL");
        boolean udpPreferred = udpEnabled && "UDP".equalsIgnoreCase(udpPriority);

        java.util.List<String> udpServerNames = new java.util.ArrayList<>();
        if (udpPreferred) {
            for (java.util.Map<?, ?> map : plugin.getConfig().getMapList("udp-sync.servers")) {
                Object nameObj = map.get("name");
                if (nameObj != null) {
                    udpServerNames.add(String.valueOf(nameObj));
                }
            }
            // 兼容旧配置（单服务器模式）
            if (udpServerNames.isEmpty() && plugin.getConfig().getString("udp-sync.host") != null) {
                udpServerNames.add(plugin.getConfig().getString("queue.main-server", "main"));
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
            return true;
        }
        sender.sendMessage(languageManager.getMessage("info-footer"));
        return true;
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
        sender.sendMessage(languageManager.getMessage("help-info"));
        sender.sendMessage(languageManager.getMessage("help-help"));
        sender.sendMessage(languageManager.getMessage("help-footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("skip", "promote", "debug", "list", "status", "refresh", "reload", "info", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 2 && ("skip".equalsIgnoreCase(args[0]) || "promote".equalsIgnoreCase(args[0]))) {
            String perm = "skip".equalsIgnoreCase(args[0]) ? "loginqueue2.admin.skip" : "loginqueue2.admin.promote";
            if (sender.hasPermission(perm)) {
                List<String> names = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        names.add(player.getName());
                    }
                }
                return names;
            }
        }
        return Collections.emptyList();
    }
}
