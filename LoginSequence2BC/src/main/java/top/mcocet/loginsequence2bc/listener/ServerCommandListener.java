package top.mcocet.loginsequence2bc.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import top.mcocet.loginsequence2bc.LoginSequence2BC;

public class ServerCommandListener implements Listener {

    private final LoginSequence2BC plugin;
    private final PluginMessageListener messageListener;

    public ServerCommandListener(LoginSequence2BC plugin, PluginMessageListener messageListener) {
        this.plugin = plugin;
        this.messageListener = messageListener;
    }

    @EventHandler
    public void onPlayerChat(ChatEvent event) {
        if (!plugin.getConfig().getBoolean("restrict-server-command", false)) {
            return;
        }

        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }

        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        String message = event.getMessage();

        // 检查是否是 /server 命令（支持各种变体）
        if (isServerCommand(message)) {
            if (!messageListener.isPlayerLoggedIn(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage("§c请先登录后再使用 /server 命令切换服务器。");
                plugin.debug("阻止玩家 " + player.getName() + " 使用 /server 命令（未登录）");
            }
        }
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        messageListener.removePlayer(event.getPlayer().getUniqueId());
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
