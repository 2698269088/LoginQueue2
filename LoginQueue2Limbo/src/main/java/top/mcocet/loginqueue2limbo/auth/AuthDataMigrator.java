package top.mcocet.loginqueue2limbo.auth;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandSender;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.database.Database;
import top.mcocet.loginqueue2limbo.database.DatabaseFactory;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.io.*;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 认证数据迁移工具 - 支持 file、SQLite、MySQL 之间的任意异步迁移
 */
public class AuthDataMigrator {

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private final AuthManager authManager;

    public AuthDataMigrator(LoginQueue2Limbo plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.authManager = plugin.getAuthManager();
    }

    /**
     * 执行异步迁移
     */
    public void migrateAsync(CommandSender sender, String from, String to, Consumer<MigrationResult> callback) {
        sendMessage(sender, "&a[迁移] 开始异步迁移: " + from + " -> " + to);

        new Thread(() -> {
            MigrationResult result;
            try {
                result = migrateSync(sender, from, to);
            } catch (Exception e) {
                Limbo.getInstance().getConsole().sendMessage("&c[迁移] 迁移过程中发生异常: " + e.getMessage());
                e.printStackTrace();
                result = new MigrationResult(false, 0, 0, "迁移异常: " + e.getMessage());
            }

            final MigrationResult finalResult = result;
            // Limbo 没有 Bukkit 的调度器，直接在主线程回调
            if (callback != null) {
                callback.accept(finalResult);
            }
        }, "LoginQueue2-Migrate").start();
    }

    /**
     * 同步执行迁移
     */
    public MigrationResult migrateSync(CommandSender sender, String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return new MigrationResult(false, 0, 0, "源和目标不能相同");
        }

        from = normalizeType(from);
        to = normalizeType(to);

        // 读取源数据
        Map<String, PlayerData> players = readFromSource(sender, from);
        if (players == null) {
            return new MigrationResult(false, 0, 0, "读取源数据失败");
        }
        if (players.isEmpty()) {
            return new MigrationResult(false, 0, 0, "源数据库中没有数据");
        }

        int total = players.size();
        int migrated = 0;
        int skipped = 0;

        sendMessage(sender, "&a[迁移] 开始将 " + from + " 数据迁移到 " + to + "，共 " + total + " 条记录...");

        for (PlayerData player : players.values()) {
            boolean result = writeToTarget(to, player);
            if (result) {
                migrated++;
            } else {
                skipped++;
            }

            if ((migrated + skipped) % 50 == 0) {
                sendMessage(sender, "&7[迁移] 进度: " + (migrated + skipped) + "/" + total);
            }
        }

        return new MigrationResult(true, migrated, skipped,
            "成功将 " + migrated + " 条数据从 " + from + " 迁移到 " + to +
            (skipped > 0 ? "，跳过/失败 " + skipped + " 条" : ""));
    }

    private String normalizeType(String type) {
        switch (type.toLowerCase()) {
            case "mysql":
                return "mysql";
            case "sqlite":
                return "sqlite";
            case "file":
            default:
                return "file";
        }
    }

    // ==================== 读取源数据 ====================

    private Map<String, PlayerData> readFromSource(CommandSender sender, String from) {
        if ("file".equals(from)) {
            return readFromFile();
        } else {
            return readFromDatabase(sender, from);
        }
    }

    private Map<String, PlayerData> readFromFile() {
        File dataFile = new File(plugin.getDataFolder(), "auth.dat");
        Map<String, PlayerData> players = new HashMap<>();

        if (!dataFile.exists()) {
            return players;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, AuthManager.AuthData> data = (Map<String, AuthManager.AuthData>) obj;
                for (AuthManager.AuthData authData : data.values()) {
                    PlayerData pd = new PlayerData();
                    pd.username = authData.username;
                    pd.hash = authData.hash;
                    pd.salt = authData.salt;
                    pd.realName = authData.realName;
                    pd.lastIp = authData.lastIp;
                    pd.lastLogin = authData.lastLogin;
                    pd.regDate = authData.regDate;
                    pd.regIp = authData.regIp;
                    players.put(pd.username.toLowerCase(), pd);
                }
            }
        } catch (Exception e) {
            Limbo.getInstance().getConsole().sendMessage("&c[迁移] 读取文件数据失败: " + e.getMessage());
        }

        return players;
    }

    private Map<String, PlayerData> readFromDatabase(CommandSender sender, String dbType) {
        Database db;
        boolean tempDb = false;

        if (authManager.getDatabase() != null && dbType.equalsIgnoreCase(authManager.getDatabase().getType())) {
            db = authManager.getDatabase();
        } else {
            db = DatabaseFactory.createDatabase(plugin, languageManager, dbType);
            tempDb = true;
        }

        if (db == null || !db.isAvailable()) {
            sendMessage(sender, "&c[迁移] " + dbType + " 数据库不可用");
            return null;
        }

        String table = getPlayersTable(db);
        Map<String, PlayerData> players = new HashMap<>();

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {

            while (rs.next()) {
                PlayerData data = new PlayerData();
                data.username = rs.getString("username");
                data.hash = rs.getString("password");
                data.salt = rs.getString("salt");
                data.realName = rs.getString("realname");
                data.lastIp = rs.getString("lastip");
                data.lastLogin = rs.getLong("lastlogin");
                data.regDate = rs.getLong("regdate");
                data.regIp = rs.getString("regip");
                players.put(data.username.toLowerCase(), data);
            }
        } catch (SQLException e) {
            sendMessage(sender, "&c[迁移] 读取 " + dbType + " 数据库失败: " + e.getMessage());
            Limbo.getInstance().getConsole().sendMessage("&c[迁移] 读取数据库失败: " + e.getMessage());
            return null;
        } finally {
            if (tempDb) {
                db.close();
            }
        }

        return players;
    }

    // ==================== 写入目标 ====================

    private boolean writeToTarget(String to, PlayerData player) {
        if ("file".equals(to)) {
            return writeToFile(player);
        } else {
            return writeToDatabase(to, player);
        }
    }

    private boolean writeToFile(PlayerData player) {
        File dataFile = new File(plugin.getDataFolder(), "auth.dat");
        Map<String, AuthManager.AuthData> fileData = loadFileData(dataFile);

        if (fileData.containsKey(player.username.toLowerCase())) {
            return false; // 已存在，跳过
        }

        AuthManager.AuthData data = new AuthManager.AuthData(
            player.username,
            player.hash,
            player.salt,
            player.realName,
            player.lastIp,
            player.regDate,
            player.regIp
        );
        data.lastLogin = player.lastLogin;
        fileData.put(player.username.toLowerCase(), data);

        saveFileData(dataFile, fileData);
        return true;
    }

    private boolean writeToDatabase(String dbType, PlayerData player) {
        Database db;
        boolean tempDb = false;

        if (authManager.getDatabase() != null && dbType.equalsIgnoreCase(authManager.getDatabase().getType())) {
            db = authManager.getDatabase();
        } else {
            db = DatabaseFactory.createDatabase(plugin, languageManager, dbType);
            tempDb = true;
        }

        if (db == null || !db.isAvailable()) {
            return false;
        }

        String table = getPlayersTable(db);

        // 检查是否已存在
        if (existsInDatabase(db, table, player.username)) {
            if (tempDb) db.close();
            return false;
        }

        String sql;
        if ("MySQL".equals(db.getType())) {
            sql = "INSERT INTO " + table + " (username, password, salt, realname, lastip, lastlogin, regdate, regip) " +
                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE password=VALUES(password), salt=VALUES(salt), realname=VALUES(realname), " +
                  "lastip=VALUES(lastip), lastlogin=VALUES(lastlogin), regdate=VALUES(regdate), regip=VALUES(regip)";
        } else {
            sql = "INSERT OR REPLACE INTO " + table + " (username, password, salt, realname, lastip, lastlogin, regdate, regip) " +
                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        }

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.username.toLowerCase());
            ps.setString(2, player.hash);
            ps.setString(3, player.salt);
            ps.setString(4, player.realName != null ? player.realName : player.username);
            ps.setString(5, player.lastIp != null ? player.lastIp : "");
            ps.setLong(6, player.lastLogin);
            ps.setLong(7, player.regDate > 0 ? player.regDate : System.currentTimeMillis());
            ps.setString(8, player.regIp != null ? player.regIp : "");
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            Limbo.getInstance().getConsole().sendMessage("&c[迁移] 插入玩家 " + player.username + " 到 " + dbType + " 失败: " + e.getMessage());
            return false;
        } finally {
            if (tempDb) {
                db.close();
            }
        }
    }

    // ==================== 工具方法 ====================

    private boolean existsInDatabase(Database db, String table, String username) {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE username = ?")) {
            ps.setString(1, username.toLowerCase());
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private String getPlayersTable(Database db) {
        if (db instanceof top.mcocet.loginqueue2limbo.database.MySQLDatabase) {
            return "`" + ((top.mcocet.loginqueue2limbo.database.MySQLDatabase) db).getTablePrefix() + "players`";
        }
        return "players";
    }

    @SuppressWarnings("unchecked")
    private Map<String, AuthManager.AuthData> loadFileData(File dataFile) {
        Map<String, AuthManager.AuthData> data = new HashMap<>();
        if (!dataFile.exists()) {
            return data;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                data.putAll((Map<String, AuthManager.AuthData>) obj);
            }
        } catch (Exception e) {
            // ignore
        }
        return data;
    }

    private void saveFileData(File dataFile, Map<String, AuthManager.AuthData> data) {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataFile))) {
                oos.writeObject(new HashMap<>(data));
            }
        } catch (IOException e) {
            Limbo.getInstance().getConsole().sendMessage("&c[迁移] 保存文件数据失败: " + e.getMessage());
        }
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(message);
        }
        Limbo.getInstance().getConsole().sendMessage(message);
    }

    // ==================== 数据类 ====================

    public static class MigrationResult {
        public final boolean success;
        public final int migrated;
        public final int skipped;
        public final String message;

        public MigrationResult(boolean success, int migrated, int skipped, String message) {
            this.success = success;
            this.migrated = migrated;
            this.skipped = skipped;
            this.message = message;
        }
    }

    private static class PlayerData {
        String username;
        String hash;
        String salt;
        String realName;
        String lastIp;
        long lastLogin;
        long regDate;
        String regIp;
    }
}
