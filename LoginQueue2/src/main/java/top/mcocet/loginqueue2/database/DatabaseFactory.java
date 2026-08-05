package top.mcocet.loginqueue2.database;

import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.loginqueue2.util.LanguageManager;

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
    public static Database createDatabase(JavaPlugin plugin, LanguageManager languageManager, String type) {
        if (type == null) {
            type = "sqlite";
        }

        switch (type.toLowerCase()) {
            case "mysql":
                return new MySQLDatabase(plugin, languageManager);
            case "sqlite":
            default:
                return new SQLiteDatabase(plugin, languageManager);
        }
    }
}
