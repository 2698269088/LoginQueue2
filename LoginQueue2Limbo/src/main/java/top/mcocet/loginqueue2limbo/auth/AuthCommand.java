package top.mcocet.loginqueue2limbo.auth;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.player.Player;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.listener.PlayerJoinListener;

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
    private final Map<UUID, Long> loginCooldown = new HashMap<>();
    private final Map<UUID, Long> registerCooldown = new HashMap<>();

    public AuthCommand(LoginQueue2Limbo plugin, AuthManager authManager, PlayerJoinListener playerJoinListener) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.playerJoinListener = playerJoinListener;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("[LoginQueue] 该命令只能由玩家执行");
            return;
        }

        Player player = (Player) sender;

        if (!authManager.isEnabled()) {
            player.sendMessage("[LoginQueue] 认证功能未启用");
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
            player.sendMessage("[LoginQueue] 用法: /register <密码> <确认密码>");
            return;
        }

        long now = System.currentTimeMillis();
        Long last = registerCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfigValueLong("auth.register-cooldown", 5) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage("[LoginQueue] 请等待 " + ((cooldown - (now - last)) / 1000 + 1) + " 秒后再注册");
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage("[LoginQueue] 两次输入的密码不一致");
            return;
        }

        int minLength = plugin.getConfigValueInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfigValueInt("auth.max-password-length", 32);
        if (password.length() < minLength) {
            player.sendMessage("[LoginQueue] 密码长度不能少于 " + minLength + " 个字符");
            return;
        }
        if (password.length() > maxLength) {
            player.sendMessage("[LoginQueue] 密码长度不能超过 " + maxLength + " 个字符");
            return;
        }

        if (authManager.isRegistered(player.getName())) {
            player.sendMessage("[LoginQueue] 你已经注册过了，请使用 /login 登录");
            return;
        }

        String ip = player.clientConnection != null && player.clientConnection.getInetAddress() != null
                ? player.clientConnection.getInetAddress().getHostAddress()
                : "unknown";
        if (authManager.register(player.getName(), password, player.getName(), ip)) {
            registerCooldown.put(player.getUniqueId(), now);
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue] 玩家 " + player.getName() + " 注册成功");

            // 注册成功后自动登录
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage("[LoginQueue] 注册成功！已自动登录。");
            player.setTitleSubTitle("§a注册成功", "§e已自动登录", 10, 70, 20);

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfigValueBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage("[LoginQueue] 正在进入排队队列...");
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage("[LoginQueue] 请使用 /join 命令手动加入排队队列");
            }
        } else {
            player.sendMessage("[LoginQueue] 注册失败，请稍后再试");
        }
    }

    private void handleLogin(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage("[LoginQueue] 用法: /login <密码>");
            return;
        }

        long now = System.currentTimeMillis();
        Long last = loginCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfigValueLong("auth.login-cooldown", 1) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage("[LoginQueue] 请等待 " + ((cooldown - (now - last)) / 1000 + 1) + " 秒后再尝试");
            return;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage("[LoginQueue] 你还没有注册，请使用 /register <密码> <确认密码> 注册");
            return;
        }

        String password = args[0];
        if (authManager.checkPassword(player.getName(), password)) {
            loginCooldown.put(player.getUniqueId(), now);
            String ip = player.clientConnection != null && player.clientConnection.getInetAddress() != null
                    ? player.clientConnection.getInetAddress().getHostAddress()
                    : "unknown";
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage("[LoginQueue] 登录成功！");
            player.setTitleSubTitle("§a登录成功", "§e欢迎回来！", 10, 70, 20);
            Limbo.getInstance().getConsole().sendMessage("[LoginQueue] 玩家 " + player.getName() + " 登录成功");

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfigValueBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage("[LoginQueue] 正在进入排队队列...");
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage("[LoginQueue] 请使用 /join 命令手动加入排队队列");
            }
        } else {
            loginCooldown.put(player.getUniqueId(), now);
            player.sendMessage("[LoginQueue] 密码错误，请重试");
        }
    }

    private void handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("[LoginQueue] 用法: /changepassword <旧密码> <新密码>");
            return;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage("[LoginQueue] 你还没有注册");
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (!authManager.checkPassword(player.getName(), oldPassword)) {
            player.sendMessage("[LoginQueue] 旧密码错误");
            return;
        }

        int minLength = plugin.getConfigValueInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfigValueInt("auth.max-password-length", 32);
        if (newPassword.length() < minLength) {
            player.sendMessage("[LoginQueue] 新密码长度不能少于 " + minLength + " 个字符");
            return;
        }
        if (newPassword.length() > maxLength) {
            player.sendMessage("[LoginQueue] 新密码长度不能超过 " + maxLength + " 个字符");
            return;
        }

        if (authManager.changePassword(player.getName(), newPassword)) {
            player.sendMessage("[LoginQueue] 密码修改成功");
        } else {
            player.sendMessage("[LoginQueue] 密码修改失败");
        }
    }
}
