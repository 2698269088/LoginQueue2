package top.mcocet.loginqueue2.auth;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Locale;
import java.util.logging.Level;

/**
 * 认证数据管理器 - 使用 SQLite 存储玩家账号数据
 */
public class AuthManager {

    private final JavaPlugin plugin;
    private final boolean enabled;
    private Connection connection;
    private final SecureRandom random = new SecureRandom();

    public AuthManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("auth.enabled", false);
        if (enabled) {
            initDatabase();
        }
    }

    private void initDatabase() {
        File dbFile = new File(plugin.getDataFolder(), "auth.db");
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS players ("
                        + "username TEXT PRIMARY KEY NOT NULL,"
                        + "password TEXT NOT NULL,"
                        + "salt TEXT NOT NULL,"
                        + "realname TEXT,"
                        + "lastip TEXT,"
                        + "lastlogin INTEGER DEFAULT 0,"
                        + "regdate INTEGER DEFAULT 0,"
                        + "regip TEXT"
                        + ")");
            }
            plugin.getLogger().info("[Auth] SQLite 数据库已初始化");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Auth] 数据库初始化失败", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检查玩家是否已注册
     */
    public boolean isRegistered(String username) {
        if (!enabled || connection == null) return false;
        String sql = "SELECT 1 FROM players WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Auth] 查询注册状态失败", e);
            return false;
        }
    }

    /**
     * 注册玩家
     */
    public boolean register(String username, String password, String realName, String ip) {
        if (!enabled || connection == null) return false;
        if (isRegistered(username)) return false;

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        String name = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        String sql = "INSERT INTO players (username, password, salt, realname, lastip, lastlogin, regdate, regip) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, realName);
            ps.setString(5, ip);
            ps.setLong(6, 0);
            ps.setLong(7, now);
            ps.setString(8, ip);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Auth] 注册玩家失败", e);
            return false;
        }
    }

    /**
     * 验证密码
     */
    public boolean checkPassword(String username, String password) {
        if (!enabled || connection == null) return false;
        String sql = "SELECT password, salt FROM players WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("password");
                    String salt = rs.getString("salt");
                    return hash.equals(hashPassword(password, salt));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Auth] 验证密码失败", e);
        }
        return false;
    }

    /**
     * 更新登录信息
     */
    public void updateLogin(String username, String ip) {
        if (!enabled || connection == null) return;
        String sql = "UPDATE players SET lastip = ?, lastlogin = ? WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, username.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Auth] 更新登录信息失败", e);
        }
    }

    /**
     * 修改密码
     */
    public boolean changePassword(String username, String newPassword) {
        if (!enabled || connection == null) return false;
        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        String sql = "UPDATE players SET password = ?, salt = ? WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, username.toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Auth] 修改密码失败", e);
            return false;
        }
    }

    private String generateSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return String.format("%032x", new BigInteger(1, bytes));
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((password + salt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return String.format("%064x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Auth] 关闭数据库失败", e);
            }
        }
    }
}
