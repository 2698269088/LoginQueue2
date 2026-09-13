package top.mcocet.loginqueue2.database;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.util.LanguageManager;

import java.sql.*;
import java.util.logging.Level;

/**
 * SQLite 数据库实现
 */
public class SQLiteDatabase implements Database {

    private final JavaPlugin plugin;
    private final LanguageManager languageManager;
    private Connection connection;

    public SQLiteDatabase(JavaPlugin plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;
        init();
    }

    @Override
    public void init() {
        java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "auth.db");
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            plugin.getLogger().info(languageManager.getLogMessage("sqlite-db-initialized"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, languageManager.getLogMessage("sqlite-db-init-failed"), e);
        }
    }

    private void createTables() {
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
            stmt.execute("CREATE TABLE IF NOT EXISTS player_locations ("
                    + "uuid TEXT PRIMARY KEY NOT NULL,"
                    + "world TEXT NOT NULL,"
                    + "x REAL NOT NULL,"
                    + "y REAL NOT NULL,"
                    + "z REAL NOT NULL,"
                    + "yaw REAL NOT NULL,"
                    + "pitch REAL NOT NULL,"
                    + "gamemode TEXT,"
                    + "inventory TEXT,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            // 表结构迁移
            migrateAddColumn("player_locations", "gamemode", "TEXT");
            migrateAddColumn("player_locations", "inventory", "TEXT");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create SQLite tables", e);
        }
    }

    private void migrateAddColumn(String tableName, String columnName, String columnType) {
        if (columnExists(tableName, columnName)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType);
            plugin.getLogger().info("Migrated " + tableName + " table: added " + columnName + " column.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate " + tableName + " table for " + columnName, e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            init();
        }
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-close-db-failed"), e);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String getType() {
        return "SQLite";
    }

    @Override
    public boolean tableExists(String tableName) {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean columnExists(String tableName, String columnName) {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }
}
