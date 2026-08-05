package top.mcocet.loginqueue2limbo.database;

import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

/**
 * 数据库工厂类
 */
public class DatabaseFactory {

    /**
     * 根据配置创建对应的数据库实例
     *
     * @param plugin          插件实例
     * @param languageManager 语言管理器
     * @param type            数据库类型 (sqlite 或 mysql)
     * @return 数据库实例
     */
    public static Database createDatabase(LoginQueue2Limbo plugin, LanguageManager languageManager, String type) {
        if (type == null) {
            type = "file";
        }

        switch (type.toLowerCase()) {
            case "mysql":
                return new MySQLDatabase(plugin, languageManager);
            case "sqlite":
                return new SQLiteDatabase(plugin, languageManager);
            case "file":
            default:
                return null;
        }
    }
}
