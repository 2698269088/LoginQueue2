package top.mcocet.loginqueue2vc.database;

import org.slf4j.Logger;
import top.mcocet.loginqueue2vc.LoginQueue2VC;

import java.io.File;
import java.sql.*;
import java.util.UUID;

public class DatabaseManager {

    private final LoginQueue2VC plugin;
    private Connection connection;

    public DatabaseManager(LoginQueue2VC plugin) {
        this.plugin = plugin;
    }

    public void init() throws SQLException {
        File dataFolder = plugin.getDataDirectory().toFile();
        if (!dataFolder.exists()) {
            dataFolder.mkdir();
        }
        File dbFile = new File(dataFolder, "playerdata.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        // 显式加载SQLite驱动，兼容shade重定位场景
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("top.mcocet.loginqueue2.libs.sqlite.JDBC");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("SQLite JDBC驱动未找到", ex);
            }
        }

        connection = DriverManager.getConnection(url);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_ip (
                    uuid TEXT PRIMARY KEY,
                    player_name TEXT NOT NULL,
                    first_login_ip TEXT,
                    last_login_ip TEXT,
                    last_login_time INTEGER DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_ip_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    ip TEXT NOT NULL,
                    change_time INTEGER DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ip_ban (
                    ip TEXT PRIMARY KEY,
                    ban_time INTEGER DEFAULT 0,
                    reason TEXT
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS auth_failures (
                    ip TEXT PRIMARY KEY,
                    failure_count INTEGER DEFAULT 0,
                    last_failure_time INTEGER DEFAULT 0
                )
                """);
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                Logger logger = plugin.getLogger();
                logger.warn("关闭数据库连接失败: {}", e.getMessage());
            }
        }
    }

    public void recordPlayerLogin(UUID uuid, String playerName, String ip) {
        if (connection == null) {
            return;
        }
        String select = "SELECT first_login_ip, last_login_ip FROM player_ip WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(select)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String firstIp = rs.getString("first_login_ip");
                String lastIp = rs.getString("last_login_ip");
                String update = "UPDATE player_ip SET player_name = ?, last_login_ip = ?, last_login_time = ? WHERE uuid = ?";
                try (PreparedStatement upd = connection.prepareStatement(update)) {
                    upd.setString(1, playerName);
                    upd.setString(2, ip);
                    upd.setLong(3, System.currentTimeMillis());
                    upd.setString(4, uuid.toString());
                    upd.executeUpdate();
                }
                if (firstIp == null || firstIp.isEmpty()) {
                    String updateFirst = "UPDATE player_ip SET first_login_ip = ? WHERE uuid = ?";
                    try (PreparedStatement updFirst = connection.prepareStatement(updateFirst)) {
                        updFirst.setString(1, ip);
                        updFirst.setString(2, uuid.toString());
                        updFirst.executeUpdate();
                    }
                }
                if (lastIp != null && !lastIp.isEmpty() && !lastIp.equals(ip)) {
                    recordIpChange(uuid, playerName, ip);
                }
            } else {
                String insert = "INSERT INTO player_ip (uuid, player_name, first_login_ip, last_login_ip, last_login_time) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ins = connection.prepareStatement(insert)) {
                    ins.setString(1, uuid.toString());
                    ins.setString(2, playerName);
                    ins.setString(3, ip);
                    ins.setString(4, ip);
                    ins.setLong(5, System.currentTimeMillis());
                    ins.executeUpdate();
                }
            }
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("记录玩家登录IP失败: {}", e.getMessage());
        }
    }

    private void recordIpChange(UUID uuid, String playerName, String ip) {
        String insert = "INSERT INTO player_ip_history (uuid, player_name, ip, change_time) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, ip);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("记录IP变化历史失败: {}", e.getMessage());
        }
    }

    public String getLastLoginIp(UUID uuid) {
        String sql = "SELECT last_login_ip FROM player_ip WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("last_login_ip");
            }
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("查询玩家上次登录IP失败: {}", e.getMessage());
        }
        return null;
    }

    public boolean isIpBanned(String ip) {
        if (connection == null) {
            return false;
        }
        String sql = "SELECT ban_time FROM ip_ban WHERE ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long banTime = rs.getLong("ban_time");
                long banDuration = Long.parseLong(plugin.getConfigString("ip-ban-duration", "86400000"));
                if (System.currentTimeMillis() - banTime < banDuration) {
                    return true;
                } else {
                    try (PreparedStatement del = connection.prepareStatement("DELETE FROM ip_ban WHERE ip = ?")) {
                        del.setString(1, ip);
                        del.executeUpdate();
                    }
                    return false;
                }
            }
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("查询IP封禁状态失败: {}", e.getMessage());
        }
        return false;
    }

    public void banIp(String ip, String reason) {
        String sql = "INSERT OR REPLACE INTO ip_ban (ip, ban_time, reason) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("封禁IP失败: {}", e.getMessage());
        }
    }

    public void unbanIp(String ip) {
        String sql = "DELETE FROM ip_ban WHERE ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("解封IP失败: {}", e.getMessage());
        }
    }

    public int getAuthFailureCount(String ip) {
        String sql = "SELECT failure_count, last_failure_time FROM auth_failures WHERE ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long lastFailureTime = rs.getLong("last_failure_time");
                long resetDuration = Long.parseLong(plugin.getConfigString("auth-failure-reset-duration", "3600000"));
                if (System.currentTimeMillis() - lastFailureTime > resetDuration) {
                    try (PreparedStatement reset = connection.prepareStatement("UPDATE auth_failures SET failure_count = 0, last_failure_time = ? WHERE ip = ?")) {
                        reset.setLong(1, System.currentTimeMillis());
                        reset.setString(2, ip);
                        reset.executeUpdate();
                    }
                    return 0;
                }
                return rs.getInt("failure_count");
            }
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("查询认证失败次数失败: {}", e.getMessage());
        }
        return 0;
    }

    public void incrementAuthFailure(String ip) {
        String sql = "INSERT INTO auth_failures (ip, failure_count, last_failure_time) VALUES (?, 1, ?) " +
                     "ON CONFLICT(ip) DO UPDATE SET failure_count = failure_count + 1, last_failure_time = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("增加认证失败次数失败: {}", e.getMessage());
        }
    }

    public void clearAuthFailures(String ip) {
        String sql = "DELETE FROM auth_failures WHERE ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("清除认证失败次数失败: {}", e.getMessage());
        }
    }

    public int getRegisteredPlayerCountByIp(String ip) {
        if (connection == null) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM player_ip WHERE first_login_ip = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            Logger logger = plugin.getLogger();
            logger.warn("查询IP注册玩家数失败: {}", e.getMessage());
        }
        return 0;
    }

    public Connection getConnection() {
        return connection;
    }
}
