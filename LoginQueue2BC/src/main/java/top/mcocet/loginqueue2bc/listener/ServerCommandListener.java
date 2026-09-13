package top.mcocet.loginqueue2bc.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import top.mcocet.loginqueue2bc.LoginQueue2BC;

import java.util.List;

public class ServerCommandListener implements Listener {

    private final LoginQueue2BC plugin;
    private final PluginMessageListener messageListener;

    public ServerCommandListener(LoginQueue2BC plugin, PluginMessageListener messageListener) {
        this.plugin = plugin;
        this.messageListener = messageListener;
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String message = event.getMessage();

        // 检查是否是 /server 命令（支持各种变体）
        if (!isServerCommand(message)) {
            return;
        }

        // 检查是否在登录服中（独立功能）
        if (isInLoginServer(player)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().getMessage("server-command-login-server"));
            plugin.debug(plugin.getLanguageManager().getLogMessage("server-command-blocked-login", "player", player.getName()));
            return;
        }

        // 检查是否未登录（独立功能）
        if (plugin.getConfig().getBoolean("restrict-server-command", false)) {
            if (!messageListener.isPlayerLoggedIn(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(plugin.getLanguageManager().getMessage("server-command-not-logged-in"));
                plugin.debug(plugin.getLanguageManager().getLogMessage("server-command-blocked-not-logged", "player", player.getName()));
            }
        }
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        messageListener.removePlayer(event.getPlayer().getUniqueId());
    }

    /**
     * 检查玩家当前是否在登录服中
     */
    private boolean isInLoginServer(ProxiedPlayer player) {
        if (!plugin.getConfig().getBoolean("restrict-login-server-command", false)) {
            return false;
        }

        if (player.getServer() == null) {
            return false;
        }

        String currentServer = player.getServer().getInfo().getName();
        List<String> loginServers = plugin.getConfig().getStringList("login-servers");

        for (String loginServer : loginServers) {
            if (loginServer.equalsIgnoreCase(currentServer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断消息是否为 /server 命令
     */
    private boolean isServerCommand(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        String lower = message.toLowerCase();
        // 支持 /server、/sv、/connect 等常见命令
        return lower.startsWith("/server ")
                || lower.equals("/server")
                || lower.startsWith("/sv ")
                || lower.equals("/sv")
                || lower.startsWith("/connect ")
                || lower.equals("/connect");
    }
}
