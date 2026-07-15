package top.mcocet.loginqueue2limbo.auth;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.player.Player;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.listener.PlayerJoinListener;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 登录/注册命令处理器（Limbo 版本）
 */
public class AuthCommand implements CommandExecutor {

    private final LoginQueue2Limbo plugin;
    private final AuthManager authManager;
    private final PlayerJoinListener playerJoinListener;
    private final LanguageManager languageManager;
    private final Map<UUID, Long> loginCooldown = new HashMap<>();
    private final Map<UUID, Long> registerCooldown = new HashMap<>();

    public AuthCommand(LoginQueue2Limbo plugin, AuthManager authManager, PlayerJoinListener playerJoinListener) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.playerJoinListener = playerJoinListener;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage(
                    "auth-player-only"));
            return;
        }

        Player player = (Player) sender;

        if (!authManager.isEnabled()) {
            player.sendMessage(languageManager.getMessage("auth-not-enabled"));
            return;
        }

        // Limbo 命令系统传入的是完整命令行，需要解析
        if (args.length == 0) {
            return;
        }

        String cmd = args[0].toLowerCase();
        String[] cmdArgs = new String[args.length - 1];
        System.arraycopy(args, 1, cmdArgs, 0, cmdArgs.length);

        switch (cmd) {
            case "register":
                handleRegister(player, cmdArgs);
                break;
            case "login":
                handleLogin(player, cmdArgs);
                break;
            case "changepassword":
            case "changepw":
                handleChangePassword(player, cmdArgs);
                break;
            default:
                break;
        }
    }

    private void handleRegister(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(languageManager.getMessage("auth-register-usage"));
            return;
        }

        long now = System.currentTimeMillis();
        Long last = registerCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfigValueLong("auth.register-cooldown", 5) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(languageManager.getMessage("auth-register-cooldown", "seconds", String.valueOf((cooldown - (now - last)) / 1000 + 1)));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(languageManager.getMessage("auth-password-mismatch"));
            return;
        }

        int minLength = plugin.getConfigValueInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfigValueInt("auth.max-password-length", 32);
        if (password.length() < minLength) {
            player.sendMessage(languageManager.getMessage("auth-password-too-short", "min", String.valueOf(minLength)));
            return;
        }
        if (password.length() > maxLength) {
            player.sendMessage(languageManager.getMessage("auth-password-too-long", "max", String.valueOf(maxLength)));
            return;
        }

        if (authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-already-registered"));
            return;
        }

        String ip = player.clientConnection != null && player.clientConnection.getInetAddress() != null
                ? player.clientConnection.getInetAddress().getHostAddress()
                : "unknown";
        if (authManager.register(player.getName(), password, player.getName(), ip)) {
            registerCooldown.put(player.getUniqueId(), now);
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-register-success", "player", player.getName()));

            // 注册成功后自动登录
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(languageManager.getMessage("auth-register-success"));
            player.setTitleSubTitle(languageManager.getMessage("auth-title-register-success"), languageManager.getMessage("auth-title-auto-logged-in"), 10, 70, 20);

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfigValueBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(languageManager.getMessage("auth-auto-joining"));
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(languageManager.getMessage("auth-manual-join"));
            }
        } else {
            player.sendMessage(languageManager.getMessage("auth-register-fail"));
        }
    }

    private void handleLogin(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(languageManager.getMessage("auth-login-usage"));
            return;
        }

        long now = System.currentTimeMillis();
        Long last = loginCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfigValueLong("auth.login-cooldown", 1) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(languageManager.getMessage("auth-login-cooldown", "seconds", String.valueOf((cooldown - (now - last)) / 1000 + 1)));
            return;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-not-registered"));
            return;
        }

        String password = args[0];
        if (authManager.checkPassword(player.getName(), password)) {
            loginCooldown.put(player.getUniqueId(), now);
            String ip = player.clientConnection != null && player.clientConnection.getInetAddress() != null
                    ? player.clientConnection.getInetAddress().getHostAddress()
                    : "unknown";
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(languageManager.getMessage("auth-login-success"));
            player.setTitleSubTitle(languageManager.getMessage("auth-title-login-success"), languageManager.getMessage("auth-title-welcome-back"), 10, 70, 20);
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-login-success", "player", player.getName()));

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfigValueBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(languageManager.getMessage("auth-auto-joining"));
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(languageManager.getMessage("auth-manual-join"));
            }
        } else {
            loginCooldown.put(player.getUniqueId(), now);
            player.sendMessage(languageManager.getMessage("auth-wrong-password"));
        }
    }

    private void handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(languageManager.getMessage("auth-change-password-usage"));
            return;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(languageManager.getMessage("auth-not-logged-in"));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (!authManager.checkPassword(player.getName(), oldPassword)) {
            player.sendMessage(languageManager.getMessage("auth-old-password-wrong"));
            return;
        }

        int minLength = plugin.getConfigValueInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfigValueInt("auth.max-password-length", 32);
        if (newPassword.length() < minLength) {
            player.sendMessage(languageManager.getMessage("auth-new-password-too-short", "min", String.valueOf(minLength)));
            return;
        }
        if (newPassword.length() > maxLength) {
            player.sendMessage(languageManager.getMessage("auth-new-password-too-long", "max", String.valueOf(maxLength)));
            return;
        }

        if (authManager.changePassword(player.getName(), newPassword)) {
            player.sendMessage(languageManager.getMessage("auth-change-password-success"));
        } else {
            player.sendMessage(languageManager.getMessage("auth-change-password-fail"));
        }
    }
}
