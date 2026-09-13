package top.mcocet.loginqueue2limbo.auth;

import com.loohp.limbo.Limbo;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.database.Database;
import top.mcocet.loginqueue2limbo.database.DatabaseFactory;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * 认证数据管理器 - 支持文件存储、SQLite、MySQL
 */
public class AuthManager {

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private final boolean enabled;
    private final String storageType;
    private final File dataFile;
    private final Map<String, AuthData> authCache = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private Database database;

    public AuthManager(LoginQueue2Limbo plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.enabled = plugin.getConfigValueBoolean("auth.enabled", false);
        this.storageType = plugin.getConfigValueString("database.type", "file");
        this.dataFile = new File(plugin.getDataFolder(), "auth.dat");

        if (enabled) {
            if ("file".equalsIgnoreCase(storageType)) {
                loadData();
            } else {
                initDatabase();
            }
        }
    }

    private void initDatabase() {
        this.database = DatabaseFactory.createDatabase(plugin, languageManager, storageType);
        if (database != null && database.isAvailable()) {
            Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("auth-db-type", "type", database.getType()));
            migrateFromFile();
        } else {
            Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("auth-db-fallback", "type", storageType));
            this.database = null;
            loadData();
        }
    }

    /**
     * 从文件迁移数据到数据库（首次切换时）
     */
    private void migrateFromFile() {
        if (!dataFile.exists()) {
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, AuthData> data = (Map<String, AuthData>) obj;
                int count = 0;
                for (AuthData authData : data.values()) {
                    if (saveToDatabase(authData)) {
                        count++;
                    }
                }
                Limbo.getInstance().getConsole().sendMessage(
                    languageManager.getLogMessage("auth-migrate-from-file", "count", String.valueOf(count)));
                // 迁移完成后重命名文件作为备份
                File backupFile = new File(plugin.getDataFolder(), "auth.dat.backup");
                dataFile.renameTo(backupFile);
            }
        } catch (Exception e) {
            Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("auth-migrate-failed", "error", e.getMessage()));
        }
    }

    private boolean saveToDatabase(AuthData data) {
        if (database == null || !database.isAvailable()) {
            return false;
        }
        String table = getPlayersTable();
        String sql;
        if ("MySQL".equals(database.getType())) {
            sql = "INSERT INTO " + table + " (username, password, salt, realname, lastip, lastlogin, regdate, regip) " +
                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE password=VALUES(password), salt=VALUES(salt), realname=VALUES(realname), " +
                  "lastip=VALUES(lastip), lastlogin=VALUES(lastlogin), regdate=VALUES(regdate), regip=VALUES(regip)";
        } else {
            sql = "INSERT OR REPLACE INTO " + table + " (username, password, salt, realname, lastip, lastlogin, regdate, regip) " +
                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        }
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, data.username);
            ps.setString(2, data.hash);
            ps.setString(3, data.salt);
            ps.setString(4, data.realName);
            ps.setString(5, data.lastIp);
            ps.setLong(6, data.lastLogin);
            ps.setLong(7, data.regDate);
            ps.setString(8, data.regIp);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private AuthData loadFromDatabase(String username) {
        if (database == null || !database.isAvailable()) {
            return null;
        }
        String table = getPlayersTable();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + table + " WHERE username = ?")) {
            ps.setString(1, username.toLowerCase());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                AuthData data = new AuthData(
                    rs.getString("username"),
                    rs.getString("password"),
                    rs.getString("salt"),
                    rs.getString("realname"),
                    rs.getString("lastip"),
                    rs.getLong("regdate"),
                    rs.getString("regip")
                );
                data.lastLogin = rs.getLong("lastlogin");
                return data;
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    private boolean deleteFromDatabase(String username) {
        if (database == null || !database.isAvailable()) {
            return false;
        }
        String table = getPlayersTable();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE username = ?")) {
            ps.setString(1, username.toLowerCase());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private String getPlayersTable() {
        if (database instanceof top.mcocet.loginqueue2limbo.database.MySQLDatabase) {
            return "`" + ((top.mcocet.loginqueue2limbo.database.MySQLDatabase) database).getTablePrefix() + "players`";
        }
        return "players";
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                authCache.putAll((Map<String, AuthData>) obj);
            }
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-data-loaded", "count", String.valueOf(authCache.size())));
        } catch (Exception e) {
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-data-load-failed", "error", e.getMessage()));
        }
    }

    private synchronized void saveData() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataFile))) {
                oos.writeObject(new HashMap<>(authCache));
            }
        } catch (IOException e) {
            Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("auth-data-save-failed", "error", e.getMessage()));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRegistered(String username) {
        if (!enabled) return false;
        String name = username.toLowerCase();
        if (database != null && database.isAvailable()) {
            return loadFromDatabase(name) != null;
        }
        return authCache.containsKey(name);
    }

    public boolean register(String username, String password, String realName, String ip) {
        if (!enabled) return false;
        String name = username.toLowerCase();
        if (isRegistered(name)) return false;

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        AuthData data = new AuthData(name, hash, salt, realName, ip, System.currentTimeMillis(), ip);

        if (database != null && database.isAvailable()) {
            return saveToDatabase(data);
        } else {
            authCache.put(name, data);
            saveData();
            return true;
        }
    }

    public boolean checkPassword(String username, String password) {
        if (!enabled) return false;
        String name = username.toLowerCase();
        AuthData data;
        if (database != null && database.isAvailable()) {
            data = loadFromDatabase(name);
        } else {
            data = authCache.get(name);
        }
        if (data == null) return false;
        return data.hash.equals(hashPassword(password, data.salt));
    }

    public void updateLogin(String username, String ip) {
        if (!enabled) return;
        String name = username.toLowerCase();
        if (database != null && database.isAvailable()) {
            String table = getPlayersTable();
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE " + table + " SET lastip = ?, lastlogin = ? WHERE username = ?")) {
                ps.setString(1, ip);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                // ignore
            }
        } else {
            AuthData data = authCache.get(name);
            if (data != null) {
                data.lastIp = ip;
                data.lastLogin = System.currentTimeMillis();
                saveData();
            }
        }
    }

    public boolean changePassword(String username, String newPassword) {
        if (!enabled) return false;
        String name = username.toLowerCase();
        if (database != null && database.isAvailable()) {
            String salt = generateSalt();
            String hash = hashPassword(newPassword, salt);
            String table = getPlayersTable();
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE " + table + " SET password = ?, salt = ? WHERE username = ?")) {
                ps.setString(1, hash);
                ps.setString(2, salt);
                ps.setString(3, name);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        } else {
            AuthData data = authCache.get(name);
            if (data == null) return false;
            data.salt = generateSalt();
            data.hash = hashPassword(newPassword, data.salt);
            saveData();
            return true;
        }
    }

    String generateSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return String.format("%032x", new BigInteger(1, bytes));
    }

    String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((password + salt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return String.format("%064x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 获取数据库实例（供外部使用）
     */
    public Database getDatabase() {
        return database;
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        if (database != null) {
            database.close();
        }
    }

    public static class AuthData implements Serializable {
        private static final long serialVersionUID = 1L;
        public String username;
        public String hash;
        public String salt;
        public String realName;
        public String lastIp;
        public long lastLogin;
        public long regDate;
        public String regIp;

        public AuthData(String username, String hash, String salt, String realName,
                 String lastIp, long regDate, String regIp) {
            this.username = username;
            this.hash = hash;
            this.salt = salt;
            this.realName = realName;
            this.lastIp = lastIp;
            this.lastLogin = 0;
            this.regDate = regDate;
            this.regIp = regIp;
        }
    }
}
