package top.mcocet.loginqueue2limbo.database;

import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.sql.*;

/**
 * SQLite 数据库实现
 */
public class SQLiteDatabase implements Database {

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private Connection connection;

    public SQLiteDatabase(LoginQueue2Limbo plugin, LanguageManager languageManager) {
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
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("sqlite-db-initialized"));
        } catch (Exception e) {
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("sqlite-db-init-failed", "error", e.getMessage()));
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
        } catch (SQLException e) {
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("sqlite-create-tables-failed", "error", e.getMessage()));
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
                com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                    languageManager.getLogMessage("auth-close-db-failed", "error", e.getMessage()));
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
