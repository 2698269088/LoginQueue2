package top.mcocet.loginqueue2vc.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import top.mcocet.loginqueue2vc.LoginQueue2VC;
import top.mcocet.loginqueue2vc.database.DatabaseManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IPLimitListener {

    private final LoginQueue2VC plugin;
    private final ProxyServer server;
    private final DatabaseManager databaseManager;
    private final Set<UUID> pendingAuth = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistPlayers = new HashSet<>();

    public IPLimitListener(LoginQueue2VC plugin, ProxyServer server, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.server = server;
        this.databaseManager = databaseManager;
        reloadWhitelist();
    }

    public void reloadWhitelist() {
        whitelistPlayers.clear();
        String whitelistStr = plugin.getConfigString("whitelist-players", "");
        if (whitelistStr != null && !whitelistStr.isEmpty()) {
            for (String name : whitelistStr.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    whitelistPlayers.add(trimmed.toLowerCase());
                }
            }
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        boolean enableIpLimit = plugin.getConfigBoolean("enable-ip-limit", true);
        boolean enableIpAuth = plugin.getConfigBoolean("enable-ip-auth", false);

        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        String playerName = event.getUsername();

        // 检查IP封禁
        if (databaseManager.isIpBanned(ip)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("你的IP已被封禁，请稍后再试。")
                            .color(NamedTextColor.RED)));
            plugin.debug("阻止玩家 " + playerName + " 登录：IP " + ip + " 已被封禁");
            return;
        }

        if (!enableIpLimit) {
            return;
        }

        int maxOnlinePerIp = Integer.parseInt(plugin.getConfigString("max-online-per-ip", "3"));
        int maxRegisterPerIp = Integer.parseInt(plugin.getConfigString("max-register-per-ip", "3"));

        // 检查同IP在线玩家数
        if (maxOnlinePerIp > 0) {
            int currentOnline = 0;
            for (Player p : server.getAllPlayers()) {
                if (p.getRemoteAddress().getAddress().getHostAddress().equals(ip)) {
                    currentOnline++;
                }
            }
            if (currentOnline >= maxOnlinePerIp) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        Component.text("该IP已达到最大在线玩家限制 (" + maxOnlinePerIp + ")，请稍后再试。")
                                .color(NamedTextColor.RED)));
                plugin.debug("阻止玩家 " + playerName + " 登录：IP " + ip + " 已达到最大在线限制 (" + currentOnline + "/" + maxOnlinePerIp + ")");
                return;
            }
        }

        // 检查同IP注册玩家数
        if (maxRegisterPerIp > 0) {
            int registeredCount = databaseManager.getRegisteredPlayerCountByIp(ip);
            UUID uuid = event.getUniqueId();
            boolean alreadyRegistered = false;
            try {
                java.sql.PreparedStatement ps = databaseManager.getConnection().prepareStatement(
                        "SELECT 1 FROM player_ip WHERE uuid = ?");
                ps.setString(1, uuid.toString());
                java.sql.ResultSet rs = ps.executeQuery();
                alreadyRegistered = rs.next();
                rs.close();
                ps.close();
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warn("查询玩家注册状态失败: " + e.getMessage());
            }

            if (!alreadyRegistered && registeredCount >= maxRegisterPerIp) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        Component.text("该IP已达到最大注册玩家限制 (" + maxRegisterPerIp + ")，无法注册新账号。")
                                .color(NamedTextColor.RED)));
                plugin.debug("阻止玩家 " + playerName + " 注册：IP " + ip + " 已达到最大注册限制 (" + registeredCount + "/" + maxRegisterPerIp + ")");
            }
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        boolean enableIpLimit = plugin.getConfigBoolean("enable-ip-limit", true);
        boolean enableIpAuth = plugin.getConfigBoolean("enable-ip-auth", false);

        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        if (enableIpLimit) {
            databaseManager.recordPlayerLogin(player.getUniqueId(), player.getUsername(), ip);
            plugin.debug("记录玩家 " + player.getUsername() + " 登录IP: " + ip);
        }

        if (!enableIpAuth) {
            return;
        }

        // 检查白名单玩家
        if (!whitelistPlayers.contains(player.getUsername().toLowerCase())) {
            return;
        }

        String lastIp = databaseManager.getLastLoginIp(player.getUniqueId());
        if (lastIp != null && !lastIp.isEmpty() && !lastIp.equals(ip)) {
            // IP发生变化，需要认证
            pendingAuth.add(player.getUniqueId());
            player.sendMessage(Component.text("[IP安全] 你的登录IP已发生变化，请输入认证密钥以继续登录。").color(NamedTextColor.RED));
            player.sendMessage(Component.text("[IP安全] 提示：直接在聊天栏输入密钥即可（不会被发送到服务器）").color(NamedTextColor.RED));
            plugin.debug("玩家 " + player.getUsername() + " IP变化，等待认证: " + lastIp + " -> " + ip);
        }
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (!plugin.getConfigBoolean("enable-ip-auth", false)) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!pendingAuth.contains(uuid)) {
            return;
        }

        // 拦截聊天消息作为认证输入
        event.setResult(PlayerChatEvent.ChatResult.denied());
        String input = event.getMessage().trim();
        String correctKey = plugin.getConfigString("auth-key", "");
        String ip = player.getRemoteAddress().getAddress().getHostAddress();

        if (input.equals(correctKey)) {
            pendingAuth.remove(uuid);
            databaseManager.clearAuthFailures(ip);
            player.sendMessage(Component.text("[IP安全] 认证成功，欢迎回来！").color(NamedTextColor.GREEN));
            plugin.debug("玩家 " + player.getUsername() + " 认证成功");
        } else {
            databaseManager.incrementAuthFailure(ip);
            int failures = databaseManager.getAuthFailureCount(ip);
            int maxFailures = Integer.parseInt(plugin.getConfigString("max-auth-failures", "3"));
            int remaining = maxFailures - failures;

            if (remaining <= 0) {
                databaseManager.banIp(ip, "认证密钥错误次数过多");
                pendingAuth.remove(uuid);
                player.disconnect(Component.text("你的IP已被封禁，原因：认证密钥错误次数过多。").color(NamedTextColor.RED));
                plugin.debug("玩家 " + player.getUsername() + " IP " + ip + " 因认证失败次数过多被封禁");
            } else {
                player.sendMessage(Component.text("[IP安全] 认证密钥错误，还剩 " + remaining + " 次机会。").color(NamedTextColor.RED));
                plugin.debug("玩家 " + player.getUsername() + " 认证失败，剩余次数: " + remaining);
            }
        }
    }
}
