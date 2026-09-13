package top.mcocet.loginqueue2limbo.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库接口定义
 */
public interface Database {

    /**
     * 获取数据库连接
     */
    Connection getConnection() throws SQLException;

    /**
     * 初始化数据库（创建表等）
     */
    void init();

    /**
     * 关闭数据库连接
     */
    void close();

    /**
     * 检查数据库是否可用
     */
    boolean isAvailable();

    /**
     * 获取数据库类型名称
     */
    String getType();

    /**
     * 检查表是否存在
     */
    boolean tableExists(String tableName);

    /**
     * 检查列是否存在
     */
    boolean columnExists(String tableName, String columnName);
}
