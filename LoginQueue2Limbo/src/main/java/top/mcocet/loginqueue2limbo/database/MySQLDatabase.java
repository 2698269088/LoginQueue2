package top.mcocet.loginqueue2limbo.database;

import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.sql.*;

/**
 * MySQL 数据库实现
 */
public class MySQLDatabase implements Database {

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String tablePrefix;
    private final boolean useSSL;
    private final boolean autoReconnect;
    private Connection connection;

    public MySQLDatabase(LoginQueue2Limbo plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;

        this.host = plugin.getConfigValueString("mysql.host", "localhost");
        this.port = plugin.getConfigValueInt("mysql.port", 3306);
        this.database = plugin.getConfigValueString("mysql.database", "loginqueue2");
        this.username = plugin.getConfigValueString("mysql.username", "root");
        this.password = plugin.getConfigValueString("mysql.password", "");
        this.tablePrefix = plugin.getConfigValueString("mysql.table-prefix", "lq2_");
        this.useSSL = plugin.getConfigValueBoolean("mysql.use-ssl", false);
        this.autoReconnect = plugin.getConfigValueBoolean("mysql.auto-reconnect", true);

        init();
    }

    @Override
    public void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connect();
            if (isAvailable()) {
                createTables();
                com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                    languageManager.getLogMessage("mysql-db-initialized",
                        "host", host, "port", String.valueOf(port), "database", database));
            }
        } catch (ClassNotFoundException e) {
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("mysql-driver-not-found", "error", e.getMessage()));
        }
    }

    private void connect() {
        try {
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSSL
                    + "&autoReconnect=" + autoReconnect
                    + "&characterEncoding=UTF-8"
                    + "&useUnicode=true";
            connection = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("mysql-connection-failed",
                    "host", host, "port", String.valueOf(port), "error", e.getMessage()));
        }
    }

    private void createTables() {
        String playersTable = tablePrefix + "players";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS `" + playersTable + "` ("
                    + "`username` VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "`password` VARCHAR(128) NOT NULL,"
                    + "`salt` VARCHAR(64) NOT NULL,"
                    + "`realname` VARCHAR(64),"
                    + "`lastip` VARCHAR(64),"
                    + "`lastlogin` BIGINT DEFAULT 0,"
                    + "`regdate` BIGINT DEFAULT 0,"
                    + "`regip` VARCHAR(64)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("mysql-tables-created"));
        } catch (SQLException e) {
            com.loohp.limbo.Limbo.getInstance().getConsole().sendMessage(
                languageManager.getLogMessage("mysql-create-tables-failed", "error", e.getMessage()));
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
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
            return connection != null && !connection.isClosed() && connection.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String getType() {
        return "MySQL";
    }

    @Override
    public boolean tableExists(String tableName) {
        String fullTableName = tablePrefix + tableName;
        try (ResultSet rs = connection.getMetaData().getTables(database, null, fullTableName, null)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public boolean columnExists(String tableName, String columnName) {
        String fullTableName = tablePrefix + tableName;
        try (ResultSet rs = connection.getMetaData().getColumns(database, null, fullTableName, columnName)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    public String getTablePrefix() {
        return tablePrefix;
    }
}