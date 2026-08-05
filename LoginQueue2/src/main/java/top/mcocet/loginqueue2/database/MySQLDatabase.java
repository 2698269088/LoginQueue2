package top.mcocet.loginqueue2.database;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.util.LanguageManager;

import java.sql.*;
import java.util.logging.Level;

/**
 * MySQL 数据库实现
 */
public class MySQLDatabase implements Database {

    private final JavaPlugin plugin;
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

    public MySQLDatabase(JavaPlugin plugin, LanguageManager languageManager) {
        this.plugin = plugin;
        this.languageManager = languageManager;

        ConfigurationSection mysqlConfig = plugin.getConfig().getConfigurationSection("mysql");
        if (mysqlConfig == null) {
            mysqlConfig = plugin.getConfig().createSection("mysql");
        }

        this.host = mysqlConfig.getString("host", "localhost");
        this.port = mysqlConfig.getInt("port", 3306);
        this.database = mysqlConfig.getString("database", "loginqueue2");
        this.username = mysqlConfig.getString("username", "root");
        this.password = mysqlConfig.getString("password", "");
        this.tablePrefix = mysqlConfig.getString("table-prefix", "lq2_");
        this.useSSL = mysqlConfig.getBoolean("use-ssl", false);
        this.autoReconnect = mysqlConfig.getBoolean("auto-reconnect", true);

        init();
    }

    @Override
    public void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connect();
            if (isAvailable()) {
                createTables();
                plugin.getLogger().info(languageManager.getLogMessage("mysql-db-initialized", "host", host, "port", String.valueOf(port), "database", database));
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, languageManager.getLogMessage("mysql-driver-not-found"), e);
        }
    }

    private void connect() {
        try {
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSSL
                    + "&autoReconnect=" + autoReconnect
                    + "&characterEncoding=UTF-8"
                    + "&useUnicode=true"
                    + "&allowPublicKeyRetrieval=true";
            connection = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, languageManager.getLogMessage("mysql-connection-failed", "host", host, "port", String.valueOf(port)), e);
        }
    }

    private void createTables() {
        String playersTable = tablePrefix + "players";
        String locationsTable = tablePrefix + "player_locations";

        try (Statement stmt = connection.createStatement()) {
            // 玩家账号表
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

            // 玩家位置表
            stmt.execute("CREATE TABLE IF NOT EXISTS `" + locationsTable + "` ("
                    + "`uuid` VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "`world` VARCHAR(128) NOT NULL,"
                    + "`x` DOUBLE NOT NULL,"
                    + "`y` DOUBLE NOT NULL,"
                    + "`z` DOUBLE NOT NULL,"
                    + "`yaw` FLOAT NOT NULL,"
                    + "`pitch` FLOAT NOT NULL,"
                    + "`gamemode` VARCHAR(32),"
                    + "`inventory` LONGTEXT,"
                    + "`updated_at` BIGINT NOT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            plugin.getLogger().info(languageManager.getLogMessage("mysql-tables-created"));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("mysql-create-tables-failed"), e);
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
                plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-close-db-failed"), e);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            if (connection != null && !connection.isClosed() && connection.isValid(3)) {
                return true;
            }
            // 连接无效，尝试重新连接
            connect();
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
