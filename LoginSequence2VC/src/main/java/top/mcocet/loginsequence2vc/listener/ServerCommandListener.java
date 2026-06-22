package top.mcocet.loginsequence2vc.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import top.mcocet.loginsequence2vc.LoginSequence2VC;

public class ServerCommandListener {

    private final LoginSequence2VC plugin;
    private final PluginMessageListener messageListener;

    public ServerCommandListener(LoginSequence2VC plugin, PluginMessageListener messageListener) {
        this.plugin = plugin;
        this.messageListener = messageListener;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!plugin.getConfigBoolean("restrict-server-command", false)) {
            return;
        }

        if (!(event.getCommandSource() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getCommandSource();
        String command = event.getCommand();

        // 检查是否是 /server 命令（支持各种变体）
        if (isServerCommand(command)) {
            if (!messageListener.isPlayerLoggedIn(player.getUniqueId())) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                player.sendMessage(net.kyori.adventure.text.Component.text("§c请先登录后再使用 /server 命令切换服务器。"));
                plugin.debug("阻止玩家 " + player.getUsername() + " 使用 /server 命令（未登录）");
            }
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        messageListener.removePlayer(event.getPlayer().getUniqueId());
    }

    /**
     * 判断命令是否为 /server 命令
     */
    private boolean isServerCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }

        String lower = command.toLowerCase();
        // 支持 /server、/sv、/connect 等常见命令
        return lower.startsWith("server ")
                || lower.equals("server")
                || lower.startsWith("sv ")
                || lower.equals("sv")
                || lower.startsWith("connect ")
                || lower.equals("connect");
    }
}
