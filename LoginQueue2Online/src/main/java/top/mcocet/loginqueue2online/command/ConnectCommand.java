package top.mcocet.loginqueue2online.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import top.mcocet.loginqueue2online.LoginQueue2Online;

import java.util.ArrayList;
import java.util.List;

public class ConnectCommand implements TabExecutor {

    private final LoginQueue2Online plugin;

    public ConnectCommand(LoginQueue2Online plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("loginqueue2online.connect")) {
            sender.sendMessage("§c你没有权限使用此指令。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§c用法: /connect <服务器名> [玩家名]");
            return true;
        }

        String serverName = args[0];
        Player target;

        if (args.length >= 2) {
            if (!sender.hasPermission("loginqueue2online.connect.others")) {
                sender.sendMessage("§c你没有权限指定其他玩家。");
                return true;
            }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§c玩家 " + args[1] + " 不在线。");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c控制台必须指定玩家名: /connect <服务器名> <玩家名>");
                return true;
            }
            target = (Player) sender;
        }

        // 使用 BungeeCord 通道发送玩家到指定服务器
        target.sendPluginMessage(plugin, "BungeeCord", buildConnectData(serverName));
        if (target.equals(sender)) {
            sender.sendMessage("§a正在连接到服务器: " + serverName);
        } else {
            sender.sendMessage("§a已将玩家 " + target.getName() + " 发送到服务器: " + serverName);
            target.sendMessage("§a正在将你转移到服务器: " + serverName);
        }
        return true;
    }

    private byte[] buildConnectData(String serverName) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(out);
        try {
            data.writeUTF("Connect");
            data.writeUTF(serverName);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("构建 Connect 数据失败: " + e.getMessage());
        }
        return out.toByteArray();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("loginqueue2online.connect")) {
            return completions;
        }

        if (args.length == 1) {
            List<String> servers = plugin.getConfig().getStringList("server-list");
            String input = args[0].toLowerCase();
            for (String server : servers) {
                if (server.toLowerCase().startsWith(input)) {
                    completions.add(server);
                }
            }
        } else if (args.length == 2 && sender.hasPermission("loginqueue2online.connect.others")) {
            String input = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) {
                    completions.add(player.getName());
                }
            }
        }
        return completions;
    }
}
