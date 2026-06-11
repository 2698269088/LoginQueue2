package top.mcocet.loginsequence2.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.bungee.BungeeMessenger;
import top.mcocet.loginsequence2.listener.PlayerJoinListener;
import top.mcocet.loginsequence2.util.LanguageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoginSequenceCommand implements CommandExecutor, TabCompleter {

    private final LoginSequence plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public LoginSequenceCommand(LoginSequence plugin, PlayerJoinListener listener) {
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
        if (!sender.hasPermission("loginsequence.admin.skip")) {
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

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.list")) {
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
        if (!sender.hasPermission("loginsequence.admin.status")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        boolean online = messenger.isMainServerOnline();
        int mainOnline = messenger.getMainServerPlayerCount();
        int maxOnline = messenger.getMainServerMaxPlayers();
        double threshold = plugin.getConfig().getDouble("queue.threshold", 0.8);

        sender.sendMessage(languageManager.getMessage("status-header"));
        sender.sendMessage(languageManager.getMessage("status-main-server", "server", messenger.getMainServer()));
        sender.sendMessage(languageManager.getMessage(online ? "status-online" : "status-offline"));
        if (online) {
            double ratio = maxOnline > 0 ? (double) mainOnline / maxOnline : 0;
            sender.sendMessage(languageManager.getMessage("status-players", "online", String.valueOf(mainOnline), "max", String.valueOf(maxOnline)));
            sender.sendMessage(languageManager.getMessage("status-ratio", "ratio", String.format("%.1f", ratio * 100)));
            sender.sendMessage(languageManager.getMessage("status-threshold", "threshold", String.format("%.1f", threshold * 100)));
            sender.sendMessage(languageManager.getMessage(ratio >= threshold ? "status-queue-paused" : "status-queue-normal"));
        }
        sender.sendMessage(languageManager.getMessage("status-queue-size", "size", String.valueOf(listener.getQueueSize())));
        sender.sendMessage(languageManager.getMessage("status-footer"));
        return true;
    }

    private boolean handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.refresh")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        plugin.getMessenger().refresh();
        sender.sendMessage(languageManager.getMessage("refreshed"));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.reload")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        languageManager.reload();
        sender.sendMessage(languageManager.getMessage("reloaded"));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.info")) {
            sender.sendMessage(languageManager.getMessage("no-permission"));
            return true;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        if (!messenger.isEnabled()) {
            sender.sendMessage(languageManager.getMessage("bungee-extension-disabled"));
            return true;
        }

        BungeeMessenger.ServerStatus status = messenger.getMainServerStatus();
        if (status == null) {
            sender.sendMessage(languageManager.getMessage("main-server-no-data"));
            return true;
        }

        sender.sendMessage(languageManager.getMessage("info-header", "server", status.getServerName()));
        sender.sendMessage(languageManager.getMessage("info-status",
                "status", status.isOnline() ? languageManager.getMessage("online") : languageManager.getMessage("offline")));
        sender.sendMessage(languageManager.getMessage("info-players",
                "online", String.valueOf(status.getOnlinePlayers()),
                "max", String.valueOf(status.getMaxPlayers())));
        sender.sendMessage(languageManager.getMessage("info-load",
                "ratio", String.format("%.1f", status.getLoadRatio() * 100)));
        sender.sendMessage(languageManager.getMessage("info-footer"));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(languageManager.getMessage("help-header"));
        sender.sendMessage(languageManager.getMessage("help-skip"));
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
            List<String> subs = Arrays.asList("skip", "list", "status", "refresh", "reload", "info", "help");
            List<String> result = new ArrayList<>();
            for (String sub : subs) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    result.add(sub);
                }
            }
            return result;
        }
        if (args.length == 2 && "skip".equalsIgnoreCase(args[0]) && sender.hasPermission("loginsequence.admin.skip")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }
}
