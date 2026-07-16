package top.mcocet.loginqueue2.auth;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 登录/注册命令处理器
 */
public class AuthCommand implements CommandExecutor {

    private final LoginQueue2 plugin;
    private final AuthManager authManager;
    private final PlayerJoinListener playerJoinListener;
    private final LanguageManager languageManager;
    private final Map<UUID, Long> loginCooldown = new HashMap<>();
    private final Map<UUID, Long> registerCooldown = new HashMap<>();

    public AuthCommand(LoginQueue2 plugin, AuthManager authManager, PlayerJoinListener playerJoinListener) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.playerJoinListener = playerJoinListener;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("auth-player-only"));
            return true;
        }

        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (!authManager.isEnabled()) {
            player.sendMessage(languageManager.getMessage("auth-not-enabled"));
            return true;
        }

        switch (cmd) {
            case "register":
                return handleRegister(player, args);
            case "login":
                return handleLogin(player, args);
            case "changepassword":
            case "changepw":
                return handleChangePassword(player, args);
            default:
                return false;
        }
    }

    private boolean handleRegister(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(languageManager.getMessage("auth-register-usage"));
            return true;
        }

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = registerCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("auth.register-cooldown", 5) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(languageManager.getMessage("auth-register-cooldown", "seconds", String.valueOf((cooldown - (now - last)) / 1000 + 1)));
            return true;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(languageManager.getMessage("auth-password-mismatch"));
            return true;
        }

        int minLength = plugin.getConfig().getInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfig().getInt("auth.max-password-length", 32);
        if (password.length() < minLength) {
            player.sendMessage(languageManager.getMessage("auth-password-too-short", "min", String.valueOf(minLength)));
            return true;
        }
        if (password.length() > maxLength) {
            player.sendMessage(languageManager.getMessage("auth-password-too-long", "max", String.valueOf(maxLength)));
            return true;
        }

        if (authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-already-registered"));
            return true;
        }

        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        if (authManager.register(player.getName(), password, player.getName(), ip)) {
            registerCooldown.put(player.getUniqueId(), now);
            plugin.getLogger().info(languageManager.getLogMessage("auth-register-success", "player", player.getName()));

            // 注册成功后自动登录
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(languageManager.getMessage("auth-register-success"));
            player.sendTitle(languageManager.getMessage("auth-title-register-success"), languageManager.getMessage("auth-title-auto-logged-in"), 10, 70, 20);

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfig().getBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(languageManager.getMessage("auth-auto-joining"));
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(languageManager.getMessage("auth-manual-join"));
                // WORLD 模式下，如果不自动排队，直接传送到主世界
                LoginWorldManager lwm = plugin.getLoginWorldManager();
                if (lwm != null && lwm.isWorldMode()) {
                    lwm.teleportToMainWorld(player);
                }
            }
        } else {
            player.sendMessage(languageManager.getMessage("auth-register-fail"));
        }
        return true;
    }

    private boolean handleLogin(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(languageManager.getMessage("auth-login-usage"));
            return true;
        }

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = loginCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("auth.login-cooldown", 1) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(languageManager.getMessage("auth-login-cooldown", "seconds", String.valueOf((cooldown - (now - last)) / 1000 + 1)));
            return true;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-not-registered"));
            return true;
        }

        String password = args[0];
        if (authManager.checkPassword(player.getName(), password)) {
            loginCooldown.put(player.getUniqueId(), now);
            String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(languageManager.getMessage("auth-login-success"));
            player.sendTitle(languageManager.getMessage("auth-title-login-success"), languageManager.getMessage("auth-title-welcome-back"), 10, 70, 20);
            plugin.getLogger().info(languageManager.getLogMessage("auth-login-success", "player", player.getName()));

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfig().getBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(languageManager.getMessage("auth-auto-joining"));
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(languageManager.getMessage("auth-manual-join"));
                // WORLD 模式下，如果不自动排队，直接传送到主世界
                LoginWorldManager lwm = plugin.getLoginWorldManager();
                if (lwm != null && lwm.isWorldMode()) {
                    lwm.teleportToMainWorld(player);
                }
            }
        } else {
            loginCooldown.put(player.getUniqueId(), now);
            player.sendMessage(languageManager.getMessage("auth-wrong-password"));
        }
        return true;
    }

    private boolean handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(languageManager.getMessage("auth-change-password-usage"));
            return true;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-not-logged-in"));
            return true;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (!authManager.checkPassword(player.getName(), oldPassword)) {
            player.sendMessage(languageManager.getMessage("auth-old-password-wrong"));
            return true;
        }

        int minLength = plugin.getConfig().getInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfig().getInt("auth.max-password-length", 32);
        if (newPassword.length() < minLength) {
            player.sendMessage(languageManager.getMessage("auth-new-password-too-short", "min", String.valueOf(minLength)));
            return true;
        }
        if (newPassword.length() > maxLength) {
            player.sendMessage(languageManager.getMessage("auth-new-password-too-long", "max", String.valueOf(maxLength)));
            return true;
        }

        if (authManager.changePassword(player.getName(), newPassword)) {
            player.sendMessage(languageManager.getMessage("auth-change-password-success"));
        } else {
            player.sendMessage(languageManager.getMessage("auth-change-password-fail"));
        }
        return true;
    }
}
