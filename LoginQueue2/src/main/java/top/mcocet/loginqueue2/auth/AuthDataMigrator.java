package top.mcocet.loginqueue2.auth;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.security.crypts.HashedPassword;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.database.Database;
import top.mcocet.loginqueue2.database.DatabaseFactory;
import top.mcocet.loginqueue2.database.MySQLDatabase;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 认证数据迁移工具 - 支持 AuthMe、MySQL、SQLite、内置数据库之间的任意迁移
 */
public class AuthDataMigrator {

    private final JavaPlugin plugin;
    private final LanguageManager languageManager;
    private final AuthManager authManager;
    private final AuthMeCompatManager authMeCompatManager;

    public AuthDataMigrator(JavaPlugin plugin, AuthManager authManager, AuthMeCompatManager authMeCompatManager) {
        this.plugin = plugin;
        this.languageManager = ((top.mcocet.loginqueue2.LoginQueue2) plugin).getLanguageManager();
        this.authManager = authManager;
        this.authMeCompatManager = authMeCompatManager;
    }

    /**
     * 判断当前是否启用了调试模式
     */
    private boolean isDebug() {
        return ((top.mcocet.loginqueue2.LoginQueue2) plugin).isDebug();
    }

    /**
     * 输出调试日志（仅在 debug 模式开启时输出）
     */
    private void debugLog(String message) {
        if (isDebug()) {
            plugin.getLogger().info("[迁移调试] " + message);
        }
    }

    /**
     * 执行迁移（异步）
     * @param sender 命令发送者
     * @param from 源类型: authme, mysql, sqlite, builtin
     * @param to 目标类型: authme, mysql, sqlite, builtin
     * @param callback 回调函数，接收迁移结果
     */
    public void migrateAsync(CommandSender sender, String from, String to, Consumer<MigrationResult> callback) {
        sendMessage(sender, "&a[迁移] 开始异步迁移: " + from + " -> " + to);

        // 使用独立线程执行迁移，兼容 Folia
        new Thread(() -> {
            MigrationResult result;
            try {
                result = migrateSync(sender, from, to);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[迁移] 迁移过程中发生异常", e);
                result = new MigrationResult(0, 0, 0, "迁移异常: " + e.getMessage());
            }

            final MigrationResult finalResult = result;
            // 使用 SchedulerUtil 回到主线程发送结果（兼容 Folia）
            SchedulerUtil.runTask(plugin, () -> {
                if (finalResult.error != null) {
                    sendMessage(sender, "&c[迁移] 迁移失败: " + finalResult.error);
                } else {
                    String summary = "总计: " + (finalResult.success + finalResult.skipped + finalResult.failed)
                            + ", 成功: " + finalResult.success
                            + ", 跳过: " + finalResult.skipped
                            + ", 失败: " + finalResult.failed;
                    sendMessage(sender, "&a[迁移] 迁移完成! " + summary);
                }
                if (callback != null) {
                    callback.accept(finalResult);
                }
            });
        }, "LoginQueue2-Migrate").start();
    }

    /**
     * 同步执行迁移
     */
    public MigrationResult migrateSync(CommandSender sender, String from, String to) {
        debugLog("migrateSync 开始: " + from + " -> " + to);

        if (from.equalsIgnoreCase(to)) {
            return new MigrationResult(0, 0, 0, "源和目标不能相同");
        }

        // 规范化类型名
        from = normalizeType(from);
        to = normalizeType(to);
        debugLog("规范化后: " + from + " -> " + to);

        // 获取源数据
        List<BuiltinPlayerData> players = readFromSource(sender, from);
        debugLog("读取源数据结果: " + (players == null ? "null" : players.size() + " 条"));
        if (players == null) {
            return new MigrationResult(0, 0, 0, "读取源数据失败");
        }
        if (players.isEmpty()) {
            return new MigrationResult(0, 0, 0, "源数据库中没有数据");
        }

        int total = players.size();
        int success = 0;
        int skipped = 0;
        int failed = 0;

        sendMessage(sender, "&a[迁移] 开始将 " + from + " 数据迁移到 " + to + "，共 " + total + " 条记录...");

        for (BuiltinPlayerData player : players) {
            debugLog("处理玩家: " + player.username);
            boolean result = writeToTarget(sender, to, player);
            debugLog("写入结果: " + player.username + " = " + result);
            if (result) {
                success++;
            } else {
                failed++;
            }

            if ((success + skipped + failed) % 50 == 0) {
                sendMessage(sender, "&7[迁移] 进度: " + (success + skipped + failed) + "/" + total);
            }
        }

        debugLog("迁移完成: 成功=" + success + ", 跳过=" + skipped + ", 失败=" + failed);
        return new MigrationResult(success, skipped, failed, null);
    }

    private String normalizeType(String type) {
        debugLog("normalizeType: " + type);
        switch (type.toLowerCase()) {
            case "mysql":
                return "mysql";
            case "sqlite":
                return "sqlite";
            case "authme":
                return "authme";
            case "builtin":
            default:
                // builtin 使用当前配置的数据库类型
                Database db = authManager.getDatabase();
                if (db != null) {
                    debugLog("builtin 映射到: " + db.getType().toLowerCase());
                    return db.getType().toLowerCase();
                }
                debugLog("builtin 映射到默认: sqlite");
                return "sqlite";
        }
    }

    /**
     * 从源读取所有玩家数据
     */
    private List<BuiltinPlayerData> readFromSource(CommandSender sender, String from) {
        if ("authme".equals(from)) {
            return readFromAuthMe(sender);
        } else {
            return readFromDatabase(sender, from);
        }
    }

    /**
     * 写入到目标
     */
    private boolean writeToTarget(CommandSender sender, String to, BuiltinPlayerData player) {
        if ("authme".equals(to)) {
            return writeToAuthMe(player);
        } else {
            return writeToDatabase(to, player);
        }
    }

    // ==================== AuthMe 读取 ====================

    private List<BuiltinPlayerData> readFromAuthMe(CommandSender sender) {
        if (!authMeCompatManager.isAuthMeAvailable()) {
            sendMessage(sender, "&c[迁移] AuthMe 插件未安装或未启用");
            return null;
        }

        AuthMeApi api = AuthMeApi.getInstance();
        if (api == null) {
            sendMessage(sender, "&c[迁移] AuthMe API 实例为空");
            return null;
        }

        List<String> registeredNames = api.getRegisteredNames();
        if (registeredNames == null || registeredNames.isEmpty()) {
            sendMessage(sender, "&e[迁移] AuthMe 中没有注册的玩家数据");
            return new ArrayList<>();
        }

        List<BuiltinPlayerData> players = new ArrayList<>();
        for (String name : registeredNames) {
            try {
                java.util.Optional<fr.xephi.authme.api.v3.AuthMePlayer> playerInfo = api.getPlayerInfo(name);
                if (!playerInfo.isPresent()) {
                    continue;
                }

                fr.xephi.authme.api.v3.AuthMePlayer authMePlayer = playerInfo.get();

                // 通过反射获取密码哈希
                String passwordHash = "";
                String salt = "";
                try {
                    java.lang.reflect.Field dataSourceField = AuthMeApi.class.getDeclaredField("dataSource");
                    dataSourceField.setAccessible(true);
                    fr.xephi.authme.datasource.DataSource dataSource = (fr.xephi.authme.datasource.DataSource) dataSourceField.get(api);
                    if (dataSource != null) {
                        PlayerAuth playerAuth = dataSource.getAuth(name);
                        if (playerAuth != null && playerAuth.getPassword() != null) {
                            HashedPassword hashedPassword = playerAuth.getPassword();
                            passwordHash = hashedPassword.getHash();
                            salt = hashedPassword.getSalt() != null ? hashedPassword.getSalt() : "";
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[迁移] 无法通过反射获取 AuthMe DataSource", e);
                }

                if (passwordHash.isEmpty()) {
                    passwordHash = authManager.hashPasswordForMigration("");
                    salt = authManager.generateSalt();
                }

                BuiltinPlayerData data = new BuiltinPlayerData();
                data.username = name;
                data.realName = authMePlayer.getName();
                data.password = passwordHash;
                data.salt = salt;
                data.lastIp = authMePlayer.getLastLoginIpAddress().orElse("");
                data.lastLogin = authMePlayer.getLastLoginDate().map(java.time.Instant::toEpochMilli).orElse(0L);
                data.regDate = authMePlayer.getRegistrationDate().toEpochMilli();
                data.regIp = authMePlayer.getRegistrationIpAddress().orElse("");
                players.add(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[迁移] 读取 AuthMe 玩家 " + name + " 时出错", e);
            }
        }

        return players;
    }

    // ==================== AuthMe 写入 ====================

    private boolean writeToAuthMe(BuiltinPlayerData player) {
        if (!authMeCompatManager.isAuthMeAvailable()) {
            return false;
        }

        AuthMeApi api = AuthMeApi.getInstance();
        if (api == null) {
            return false;
        }

        if (api.isRegistered(player.username)) {
            return false; // 已存在，跳过
        }

        try {
            java.lang.reflect.Field dataSourceField = AuthMeApi.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            fr.xephi.authme.datasource.DataSource dataSource = (fr.xephi.authme.datasource.DataSource) dataSourceField.get(api);

            if (dataSource == null) {
                return false;
            }

            HashedPassword hashedPassword = new HashedPassword(player.password, player.salt.isEmpty() ? null : player.salt);
            PlayerAuth auth = PlayerAuth.builder()
                    .name(player.username)
                    .realName(player.realName != null ? player.realName : player.username)
                    .password(hashedPassword)
                    .lastIp(player.lastIp)
                    .lastLogin(player.lastLogin > 0 ? player.lastLogin : null)
                    .registrationIp(player.regIp)
                    .registrationDate(player.regDate > 0 ? player.regDate : System.currentTimeMillis())
                    .build();

            return dataSource.saveAuth(auth);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[迁移] 无法通过反射保存到 AuthMe: " + player.username, e);
            return false;
        }
    }

    // ==================== 数据库读取 ====================

    private List<BuiltinPlayerData> readFromDatabase(CommandSender sender, String dbType) {
        Database db;
        if (dbType.equalsIgnoreCase(authManager.getDatabase().getType())) {
            db = authManager.getDatabase();
        } else {
            db = DatabaseFactory.createDatabase(plugin, languageManager, dbType);
        }

        if (db == null || !db.isAvailable()) {
            sendMessage(sender, "&c[迁移] " + dbType + " 数据库不可用");
            return null;
        }

        String table = getPlayersTable(db);
        List<BuiltinPlayerData> players = new ArrayList<>();

        Connection conn = null;
        try {
            conn = db.getConnection();
            if (conn == null) {
                sendMessage(sender, "&c[迁移] 无法获取 " + dbType + " 数据库连接");
                return null;
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                while (rs.next()) {
                    BuiltinPlayerData data = new BuiltinPlayerData();
                    data.username = rs.getString("username");
                    data.realName = rs.getString("realname");
                    data.password = rs.getString("password");
                    data.salt = rs.getString("salt");
                    data.lastIp = rs.getString("lastip");
                    data.lastLogin = rs.getLong("lastlogin");
                    data.regDate = rs.getLong("regdate");
                    data.regIp = rs.getString("regip");
                    players.add(data);
                }
            }
        } catch (SQLException e) {
            sendMessage(sender, "&c[迁移] 读取 " + dbType + " 数据库失败: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "[迁移] 读取数据库失败", e);
            return null;
        } finally {
            // 如果创建的是临时数据库连接，关闭它
            if (db != authManager.getDatabase()) {
                db.close();
            }
        }

        return players;
    }

    // ==================== 数据库写入 ====================

    private boolean writeToDatabase(String dbType, BuiltinPlayerData player) {
        debugLog("开始写入玩家: " + player.username + " 到 " + dbType);

        Database db;
        boolean tempDb = false;

        if (authManager.getDatabase() != null && dbType.equalsIgnoreCase(authManager.getDatabase().getType())) {
            db = authManager.getDatabase();
            debugLog("使用现有数据库: " + db.getType() + ", available=" + db.isAvailable());
        } else {
            debugLog("创建临时数据库: " + dbType);
            db = DatabaseFactory.createDatabase(plugin, languageManager, dbType);
            tempDb = true;
            if (db != null) {
                debugLog("临时数据库创建成功: " + db.getType() + ", available=" + db.isAvailable());
            } else {
                plugin.getLogger().warning("[迁移] 临时数据库创建失败: " + dbType);
            }
        }

        if (db == null) {
            debugLog("数据库为null");
            return false;
        }
        if (!db.isAvailable()) {
            debugLog("数据库不可用: " + dbType);
            return false;
        }

        String table = getPlayersTable(db);
        debugLog("表名: " + table);

        // 检查是否已存在
        boolean exists = existsInDatabase(db, table, player.username);
        debugLog("玩家 " + player.username + " 已存在: " + exists);
        if (exists) {
            if (tempDb) db.close();
            return false; // 已存在，跳过
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
        debugLog("SQL: " + sql);

        Connection conn = null;
        try {
            conn = db.getConnection();
            if (conn == null) {
                debugLog("无法获取连接");
                return false;
            }
            debugLog("连接获取成功");

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.username.toLowerCase(Locale.ROOT));
                ps.setString(2, player.password);
                ps.setString(3, player.salt);
                ps.setString(4, player.realName != null ? player.realName : player.username);
                ps.setString(5, player.lastIp != null ? player.lastIp : "");
                ps.setLong(6, player.lastLogin);
                ps.setLong(7, player.regDate > 0 ? player.regDate : System.currentTimeMillis());
                ps.setString(8, player.regIp != null ? player.regIp : "");

                debugLog("执行插入: " + player.username);
                int updated = ps.executeUpdate();
                debugLog("插入成功: " + player.username + ", updated=" + updated);
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[迁移] 插入玩家 " + player.username + " 到 " + dbType + " 失败: " + e.getMessage(), e);
            return false;
        } finally {
            if (tempDb) {
                db.close();
                debugLog("临时数据库已关闭");
            }
        }
    }

    private boolean existsInDatabase(Database db, String table, String username) {
        Connection conn = null;
        try {
            conn = db.getConnection();
            if (conn == null) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM " + table + " WHERE username = ?")) {
                ps.setString(1, username.toLowerCase(Locale.ROOT));
                ResultSet rs = ps.executeQuery();
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private String getPlayersTable(Database db) {
        if (db instanceof MySQLDatabase) {
            return ((MySQLDatabase) db).getTablePrefix() + "players";
        }
        return "players";
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
        }
        plugin.getLogger().info(org.bukkit.ChatColor.stripColor(message));
    }

    /**
     * 迁移结果
     */
    public static class MigrationResult {
        public final int success;
        public final int skipped;
        public final int failed;
        public final String error;

        public MigrationResult(int success, int skipped, int failed, String error) {
            this.success = success;
            this.skipped = skipped;
            this.failed = failed;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * 内置数据库玩家数据
     */
    private static class BuiltinPlayerData {
        String username;
        String realName;
        String password;
        String salt;
        String lastIp;
        long lastLogin;
        long regDate;
        String regIp;
    }
}