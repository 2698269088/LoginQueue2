package top.mcocet.loginqueue2.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.util.LanguageManager;

/**
 * AuthMe 插件兼容管理器
 * 当内置 auth 未启用时，可选使用 AuthMe 插件进行玩家登录状态验证
 */
public class AuthMeCompatManager {

    private final JavaPlugin plugin;
    private final LanguageManager languageManager;
    private final boolean enabled;
    private AuthMeApi authMeApi;
    private boolean authMeAvailable = false;

    public AuthMeCompatManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.languageManager = ((top.mcocet.loginqueue2.LoginQueue2) plugin).getLanguageManager();
        // AuthMe 兼容模式独立配置，不再与内置 auth 互斥
        this.enabled = plugin.getConfig().getBoolean("auth.authme-compat-mode", false);

        if (enabled) {
            initAuthMe();
        }
    }

    private void initAuthMe() {
        // 检查是否安装了 AuthMe 插件
        if (plugin.getServer().getPluginManager().getPlugin("AuthMe") == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("authme-not-found"));
            return;
        }

        // 获取 AuthMe API 实例
        this.authMeApi = AuthMeApi.getInstance();
        if (authMeApi == null) {
            plugin.getLogger().warning(languageManager.getLogMessage("authme-api-null"));
            return;
        }

        this.authMeAvailable = true;
        plugin.getLogger().info(languageManager.getLogMessage("authme-compat-enabled"));
    }

    /**
     * 检查 AuthMe 兼容模式是否启用且可用
     */
    public boolean isEnabled() {
        return enabled && authMeAvailable;
    }

    /**
     * 检查 AuthMe 兼容模式是否配置为启用（不管 AuthMe 是否安装）
     */
    public boolean isConfigured() {
        return enabled;
    }

    /**
     * 检查玩家是否已通过 AuthMe 认证
     * @param player 玩家
     * @return true 如果玩家已登录或未启用此功能
     */
    public boolean isAuthenticated(Player player) {
        if (!isEnabled() || player == null) {
            return true;
        }
        return authMeApi.isAuthenticated(player);
    }

    /**
     * 检查 AuthMe 插件是否可用
     */
    public boolean isAuthMeAvailable() {
        return authMeAvailable;
    }
}
