package top.mcocet.loginqueue2vc.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import top.mcocet.loginqueue2vc.LoginQueue2VC;

import java.util.Arrays;
import java.util.List;

public class ServerCommandListener {

    private final LoginQueue2VC plugin;
    private final PluginMessageListener messageListener;

    public ServerCommandListener(LoginQueue2VC plugin, PluginMessageListener messageListener) {
        this.plugin = plugin;
        this.messageListener = messageListener;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getCommandSource();
        String command = event.getCommand();

        // 检查是否是 /server 命令（支持各种变体）
        if (!isServerCommand(command)) {
            return;
        }

        // 检查是否在登录服中（独立功能）
        if (isInLoginServer(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            player.sendMessage(net.kyori.adventure.text.Component.text("§c你当前在登录服，无法使用 /server 命令切换服务器。"));
            plugin.debug("阻止玩家 " + player.getUsername() + " 使用 /server 命令（在登录服中）");
            return;
        }

        // 检查是否未登录（独立功能）
        if (plugin.getConfigBoolean("restrict-server-command", false)) {
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
     * 检查玩家当前是否在登录服中
     */
    private boolean isInLoginServer(Player player) {
        if (!plugin.getConfigBoolean("restrict-login-server-command", false)) {
            return false;
        }

        if (!player.getCurrentServer().isPresent()) {
            return false;
        }

        String currentServer = player.getCurrentServer().get().getServerInfo().getName();
        String loginServersStr = plugin.getConfigString("login-servers", "lobby,login");
        List<String> loginServers = Arrays.asList(loginServersStr.split(","));

        for (String loginServer : loginServers) {
            String trimmed = loginServer.trim();
            if (!trimmed.isEmpty() && trimmed.equalsIgnoreCase(currentServer)) {
                return true;
            }
        }
        return false;
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
