package top.mcocet.loginqueue2.auth;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.listener.PlayerJoinListener;
import top.mcocet.loginqueue2.util.LanguageManager;

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
            sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
            return true;
        }

        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (!authManager.isEnabled()) {
            player.sendMessage(ChatColor.RED + "认证功能未启用");
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
            player.sendMessage(ChatColor.YELLOW + "用法: /register <密码> <确认密码>");
            return true;
        }

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = registerCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("auth.register-cooldown", 5) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(ChatColor.RED + "请等待 " + ((cooldown - (now - last)) / 1000 + 1) + " 秒后再注册");
            return true;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(ChatColor.RED + "两次输入的密码不一致");
            return true;
        }

        int minLength = plugin.getConfig().getInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfig().getInt("auth.max-password-length", 32);
        if (password.length() < minLength) {
            player.sendMessage(ChatColor.RED + "密码长度不能少于 " + minLength + " 个字符");
            return true;
        }
        if (password.length() > maxLength) {
            player.sendMessage(ChatColor.RED + "密码长度不能超过 " + maxLength + " 个字符");
            return true;
        }

        if (authManager.isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "你已经注册过了，请使用 /login 登录");
            return true;
        }

        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
        if (authManager.register(player.getName(), password, player.getName(), ip)) {
            registerCooldown.put(player.getUniqueId(), now);
            plugin.getLogger().info("[Auth] 玩家 " + player.getName() + " 注册成功");

            // 注册成功后自动登录
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(ChatColor.GREEN + "注册成功！已自动登录。");
            player.sendTitle(ChatColor.GREEN + "注册成功", ChatColor.YELLOW + "已自动登录", 10, 70, 20);

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfig().getBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(ChatColor.GREEN + "正在进入排队队列...");
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(ChatColor.YELLOW + "请使用 /join 命令手动加入排队队列");
            }
        } else {
            player.sendMessage(ChatColor.RED + "注册失败，请稍后再试");
        }
        return true;
    }

    private boolean handleLogin(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.YELLOW + "用法: /login <密码>");
            return true;
        }

        // 冷却检查
        long now = System.currentTimeMillis();
        Long last = loginCooldown.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("auth.login-cooldown", 1) * 1000L;
        if (last != null && now - last < cooldown) {
            player.sendMessage(ChatColor.RED + "请等待 " + ((cooldown - (now - last)) / 1000 + 1) + " 秒后再尝试");
            return true;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "你还没有注册，请使用 /register <密码> <确认密码> 注册");
            return true;
        }

        String password = args[0];
        if (authManager.checkPassword(player.getName(), password)) {
            loginCooldown.put(player.getUniqueId(), now);
            String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";
            authManager.updateLogin(player.getName(), ip);
            player.sendMessage(ChatColor.GREEN + "登录成功！");
            player.sendTitle(ChatColor.GREEN + "登录成功", ChatColor.YELLOW + "欢迎回来！", 10, 70, 20);
            plugin.getLogger().info("[Auth] 玩家 " + player.getName() + " 登录成功");

            // 标记玩家为已认证，并加入允许集合
            plugin.getAuthRestrictionListener().setAuthenticated(player.getUniqueId());
            playerJoinListener.getAllowedPlayers().add(player.getUniqueId());

            // 根据配置决定是否自动加入队列
            boolean autoQueue = plugin.getConfig().getBoolean("auth.auto-queue-after-login", true);
            if (autoQueue) {
                player.sendMessage(ChatColor.GREEN + "正在进入排队队列...");
                playerJoinListener.addPlayerToQueue(player);
            } else {
                player.sendMessage(ChatColor.YELLOW + "请使用 /join 命令手动加入排队队列");
            }
        } else {
            loginCooldown.put(player.getUniqueId(), now);
            player.sendMessage(ChatColor.RED + "密码错误，请重试");
        }
        return true;
    }

    private boolean handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "用法: /changepassword <旧密码> <新密码>");
            return true;
        }

        if (!authManager.isRegistered(player.getName())) {
            player.sendMessage(ChatColor.RED + "你还没有注册");
            return true;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (!authManager.checkPassword(player.getName(), oldPassword)) {
            player.sendMessage(ChatColor.RED + "旧密码错误");
            return true;
        }

        int minLength = plugin.getConfig().getInt("auth.min-password-length", 4);
        int maxLength = plugin.getConfig().getInt("auth.max-password-length", 32);
        if (newPassword.length() < minLength) {
            player.sendMessage(ChatColor.RED + "新密码长度不能少于 " + minLength + " 个字符");
            return true;
        }
        if (newPassword.length() > maxLength) {
            player.sendMessage(ChatColor.RED + "新密码长度不能超过 " + maxLength + " 个字符");
            return true;
        }

        if (authManager.changePassword(player.getName(), newPassword)) {
            player.sendMessage(ChatColor.GREEN + "密码修改成功");
        } else {
            player.sendMessage(ChatColor.RED + "密码修改失败");
        }
        return true;
    }
}
