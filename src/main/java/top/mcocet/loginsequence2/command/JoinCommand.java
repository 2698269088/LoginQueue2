package top.mcocet.loginsequence2.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.listener.PlayerJoinListener;

public class JoinCommand implements CommandExecutor {

    private final LoginSequence plugin;
    private final PlayerJoinListener listener;

    public JoinCommand(LoginSequence plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
            return true;
        }

        Player player = (Player) sender;

        if (listener.isInQueue(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "你已经在排队队列中了");
            return true;
        }

        if (!plugin.getMessenger().isMainServerOnline()) {
            String msg = ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("messages.main-offline", "&c[登录队列] &f主服务器当前离线，请稍后再试..."));
            player.sendMessage(msg);
            return true;
        }

        listener.addPlayerToQueue(player);
        player.sendMessage(ChatColor.GREEN + "你已加入排队队列");
        return true;
    }
}
