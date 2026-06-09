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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LoginSequenceCommand implements CommandExecutor, TabCompleter {

    private final LoginSequence plugin;
    private final PlayerJoinListener listener;

    public LoginSequenceCommand(LoginSequence plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
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
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "未知子命令，使用 /logseq help 查看帮助");
                return true;
        }
    }

    private boolean handleSkip(CommandSender sender, String[] args) {
        if (!sender.hasPermission("loginsequence.admin.skip")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "玩家 " + args[1] + " 不在线");
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "控制台使用请指定玩家：/logseq skip <玩家名>");
            return true;
        }

        listener.allowPlayerDirectly(target);
        sender.sendMessage(ChatColor.GREEN + "已允许玩家 " + target.getName() + " 跳过排队进入服务器");
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.list")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令");
            return true;
        }

        List<String> queueList = listener.getQueuePlayerNames();
        sender.sendMessage(ChatColor.GREEN + "========== 当前排队玩家 ==========");
        if (queueList.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "当前没有玩家正在排队");
        } else {
            for (int i = 0; i < queueList.size(); i++) {
                sender.sendMessage(ChatColor.YELLOW + String.valueOf(i + 1) + ". " + queueList.get(i));
            }
        }
        sender.sendMessage(ChatColor.GREEN + "==================================");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.status")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令");
            return true;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        boolean online = messenger.isMainServerOnline();
        int mainOnline = messenger.getMainServerPlayerCount();
        int maxOnline = messenger.getMainServerMaxPlayers();
        double threshold = plugin.getConfig().getDouble("queue.threshold", 0.8);

        sender.sendMessage(ChatColor.GREEN + "========== 服务器状态 ==========");
        sender.sendMessage(ChatColor.YELLOW + "主服务器: " + messenger.getMainServer());
        sender.sendMessage(ChatColor.YELLOW + "主服务器状态: " + (online ? ChatColor.GREEN + "在线" : ChatColor.RED + "离线"));
        if (online) {
            sender.sendMessage(ChatColor.YELLOW + "主服在线人数: " + mainOnline + "/" + maxOnline);
            double ratio = maxOnline > 0 ? (double) mainOnline / maxOnline : 0;
            sender.sendMessage(ChatColor.YELLOW + "负载比例: " + String.format("%.1f%%", ratio * 100));
            sender.sendMessage(ChatColor.YELLOW + "阈值: " + String.format("%.1f%%", threshold * 100));
            sender.sendMessage(ChatColor.YELLOW + "队列状态: " + (ratio >= threshold ? ChatColor.RED + "暂停放行" : ChatColor.GREEN + "正常放行"));
        }
        sender.sendMessage(ChatColor.YELLOW + "当前排队人数: " + listener.getQueueSize());
        sender.sendMessage(ChatColor.GREEN + "===============================");
        return true;
    }

    private boolean handleRefresh(CommandSender sender) {
        if (!sender.hasPermission("loginsequence.admin.refresh")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令");
            return true;
        }

        plugin.getMessenger().refresh();
        sender.sendMessage(ChatColor.GREEN + "已手动刷新主服务器状态");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "========== LoginSequence 帮助 ==========");
        sender.sendMessage(ChatColor.YELLOW + "/logseq skip [玩家名]" + ChatColor.WHITE + " - 跳过排队，直接进入服务器");
        sender.sendMessage(ChatColor.YELLOW + "/logseq list" + ChatColor.WHITE + " - 显示当前排队玩家列表");
        sender.sendMessage(ChatColor.YELLOW + "/logseq status" + ChatColor.WHITE + " - 显示服务器状态");
        sender.sendMessage(ChatColor.YELLOW + "/logseq refresh" + ChatColor.WHITE + " - 手动刷新主服务器状态");
        sender.sendMessage(ChatColor.YELLOW + "/logseq help" + ChatColor.WHITE + " - 显示此帮助");
        sender.sendMessage(ChatColor.GREEN + "========================================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("skip", "list", "status", "refresh", "help");
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
