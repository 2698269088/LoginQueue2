package top.mcocet.loginqueue2bc.listener;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import top.mcocet.loginqueue2bc.LoginQueue2BC;
import top.mcocet.loginqueue2bc.database.DatabaseManager;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IPLimitListener implements Listener {

    private final LoginQueue2BC plugin;
    private final DatabaseManager databaseManager;
    private final Set<UUID> pendingAuth = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistPlayers = new HashSet<>();

    public IPLimitListener(LoginQueue2BC plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        reloadWhitelist();
    }

    public void reloadWhitelist() {
        whitelistPlayers.clear();
        List<String> list = plugin.getConfig().getStringList("whitelist-players");
        if (list != null) {
            for (String name : list) {
                whitelistPlayers.add(name.toLowerCase());
            }
        }
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent event) {
        boolean enableIpLimit = plugin.getConfig().getBoolean("enable-ip-limit", true);
        boolean enableIpAuth = plugin.getConfig().getBoolean("enable-ip-auth", false);

        String ip = event.getConnection().getAddress().getAddress().getHostAddress();
        String playerName = event.getConnection().getName();

        // 检查IP封禁
        if (databaseManager.isIpBanned(ip)) {
            event.setCancelled(true);
            event.setCancelReason(plugin.getLanguageManager().getMessage("ip-banned"));
            plugin.debug(plugin.getLanguageManager().getLogMessage("ip-blocked", "player", playerName, "ip", ip));
            return;
        }

        if (!enableIpLimit) {
            return;
        }

        int maxOnlinePerIp = plugin.getConfig().getInt("max-online-per-ip", 3);
        int maxRegisterPerIp = plugin.getConfig().getInt("max-register-per-ip", 3);

        // 检查同IP在线玩家数
        if (maxOnlinePerIp > 0) {
            int currentOnline = 0;
            for (ProxiedPlayer p : plugin.getProxy().getPlayers()) {
                if (p.getAddress().getAddress().getHostAddress().equals(ip)) {
                    currentOnline++;
                }
            }
            if (currentOnline >= maxOnlinePerIp) {
                event.setCancelled(true);
                event.setCancelReason(plugin.getLanguageManager().getMessage("ip-max-online-reached", "max", String.valueOf(maxOnlinePerIp)));
                plugin.debug(plugin.getLanguageManager().getLogMessage("ip-max-online-blocked", "player", playerName, "ip", ip, "current", String.valueOf(currentOnline), "max", String.valueOf(maxOnlinePerIp)));
                return;
            }
        }

        // 检查同IP注册玩家数
        if (maxRegisterPerIp > 0) {
            int registeredCount = databaseManager.getRegisteredPlayerCountByIp(ip);
            UUID uuid = event.getConnection().getUniqueId();
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
                plugin.getLogger().warning(plugin.getLanguageManager().getLogMessage("db-query-failed", "error", e.getMessage()));
            }

            if (!alreadyRegistered && registeredCount >= maxRegisterPerIp) {
                event.setCancelled(true);
                event.setCancelReason(plugin.getLanguageManager().getMessage("ip-max-register-reached", "max", String.valueOf(maxRegisterPerIp)));
                plugin.debug(plugin.getLanguageManager().getLogMessage("ip-max-register-blocked", "player", playerName, "ip", ip, "current", String.valueOf(registeredCount), "max", String.valueOf(maxRegisterPerIp)));
            }
        }
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        boolean enableIpLimit = plugin.getConfig().getBoolean("enable-ip-limit", true);
        boolean enableIpAuth = plugin.getConfig().getBoolean("enable-ip-auth", false);

        ProxiedPlayer player = event.getPlayer();
        String ip = player.getAddress().getAddress().getHostAddress();

        if (enableIpLimit) {
            databaseManager.recordPlayerLogin(player.getUniqueId(), player.getName(), ip);
            plugin.debug(plugin.getLanguageManager().getLogMessage("ip-record-login", "player", player.getName(), "ip", ip));
        }

        if (!enableIpAuth) {
            return;
        }

        // 检查白名单玩家
        if (!whitelistPlayers.contains(player.getName().toLowerCase())) {
            return;
        }

        String lastIp = databaseManager.getLastLoginIp(player.getUniqueId());
        if (lastIp != null && !lastIp.isEmpty() && !lastIp.equals(ip)) {
            // IP发生变化，需要认证
            pendingAuth.add(player.getUniqueId());
            player.sendMessage(plugin.getLanguageManager().getMessage("ip-auth-required"));
            player.sendMessage(plugin.getLanguageManager().getMessage("ip-auth-hint"));
            plugin.debug(plugin.getLanguageManager().getLogMessage("ip-auth-pending", "player", player.getName(), "oldIp", lastIp, "newIp", ip));
        }
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (!plugin.getConfig().getBoolean("enable-ip-auth", false)) {
            return;
        }
        if (!(event.getSender() instanceof ProxiedPlayer)) {
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        UUID uuid = player.getUniqueId();

        if (!pendingAuth.contains(uuid)) {
            return;
        }

        // 拦截聊天消息作为认证输入
        event.setCancelled(true);
        String input = event.getMessage().trim();
        String correctKey = plugin.getConfig().getString("auth-key", "");
        String ip = player.getAddress().getAddress().getHostAddress();

        if (input.equals(correctKey)) {
            pendingAuth.remove(uuid);
            databaseManager.clearAuthFailures(ip);
            player.sendMessage(plugin.getLanguageManager().getMessage("ip-auth-success"));
            plugin.debug(plugin.getLanguageManager().getLogMessage("ip-auth-success", "player", player.getName()));
        } else {
            databaseManager.incrementAuthFailure(ip);
            int failures = databaseManager.getAuthFailureCount(ip);
            int maxFailures = plugin.getConfig().getInt("max-auth-failures", 3);
            int remaining = maxFailures - failures;

            if (remaining <= 0) {
                databaseManager.banIp(ip, "认证密钥错误次数过多");
                pendingAuth.remove(uuid);
                player.disconnect(plugin.getLanguageManager().getMessage("ip-auth-banned"));
                plugin.debug(plugin.getLanguageManager().getLogMessage("ip-auth-banned", "player", player.getName(), "ip", ip));
            } else {
                player.sendMessage(plugin.getLanguageManager().getMessage("ip-auth-fail", "remaining", String.valueOf(remaining)));
                plugin.debug(plugin.getLanguageManager().getLogMessage("ip-auth-fail", "player", player.getName(), "remaining", String.valueOf(remaining)));
            }
        }
    }
}
