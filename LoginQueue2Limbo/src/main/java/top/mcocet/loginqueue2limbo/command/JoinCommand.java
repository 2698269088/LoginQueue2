package top.mcocet.loginqueue2limbo.command;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.scheduler.LimboTask;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.listener.PlayerJoinListener;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

public class JoinCommand implements CommandExecutor {

    private final LoginQueue2Limbo plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public JoinCommand(LoginQueue2Limbo plugin, PlayerJoinListener listener) {
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
        if (!cmd.equals("join")) {
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("command-player-only"));
            return;
        }

        Player player = (Player) sender;

        // 认证模式下，未登录玩家不能使用 /join 命令
        if (plugin.getAuthManager().isEnabled()
                && plugin.getAuthRestrictionListener() != null
                && !plugin.getAuthRestrictionListener().isAuthenticated(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("auth-please-login-command"));
            return;
        }

        if (listener.isInQueue(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("already-in-queue"));
            return;
        }

        // 检查是否有任何主服务器在线（基于缓存）
        boolean anyOnline = false;
        for (BungeeMessenger.ServerStatus status : plugin.getMessenger().getAllServerStatus().values()) {
            if (status.isOnline()) {
                anyOnline = true;
                break;
            }
        }

        if (anyOnline) {
            // 缓存中有在线服务器，直接入队
            listener.addPlayerToQueue(player);
            player.sendMessage(languageManager.getMessage("joined-queue"));
            return;
        }

        // 缓存中没有在线服务器，进行实时检测（BC 优先模式下首次连接时缓存可能为空）
        player.sendMessage(languageManager.getMessage("checking-main-server"));
        plugin.getMessenger().checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
            Limbo.getInstance().getScheduler().runTask(plugin, new LimboTask() {
                @Override
                public void run() {
                    if (throwable != null || !online) {
                        player.sendMessage(languageManager.getMessage("main-offline"));
                        return;
                    }

                    // 再次检查是否已在队列中（异步期间可能状态变化）
                    if (listener.isInQueue(player.getUniqueId())) {
                        return;
                    }

                    listener.addPlayerToQueue(player);
                    player.sendMessage(languageManager.getMessage("joined-queue"));
                }
            });
        });
    }
}
