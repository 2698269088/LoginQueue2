package top.mcocet.loginqueue2.auth;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import top.mcocet.loginqueue2.database.Database;
import top.mcocet.loginqueue2.database.DatabaseFactory;
import top.mcocet.loginqueue2.database.MySQLDatabase;
import top.mcocet.loginqueue2.util.LanguageManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 认证数据管理器 - 支持 SQLite 和 MySQL 存储玩家账号数据
 */
public class AuthManager {

    private final JavaPlugin plugin;
    private final LanguageManager languageManager;
    private final boolean enabled;
    private Database database;
    private final SecureRandom random = new SecureRandom();
    private final String playersTable;
    private final String locationsTable;

    public AuthManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.languageManager = ((top.mcocet.loginqueue2.LoginQueue2) plugin).getLanguageManager();
        this.enabled = plugin.getConfig().getBoolean("auth.enabled", false);

        String dbType = plugin.getConfig().getString("database.type", "sqlite");
        this.database = DatabaseFactory.createDatabase(plugin, languageManager, dbType);

        // 设置表名（MySQL 使用前缀，SQLite 不使用）
        if (database instanceof MySQLDatabase) {
            String prefix = ((MySQLDatabase) database).getTablePrefix();
            this.playersTable = prefix + "players";
            this.locationsTable = prefix + "player_locations";
        } else {
            this.playersTable = "players";
            this.locationsTable = "player_locations";
        }

        if (enabled && database.isAvailable()) {
            plugin.getLogger().info(languageManager.getLogMessage("auth-db-initialized", "type", database.getType()));
        }
    }

    /**
     * 获取数据库连接
     */
    private Connection getConnection() {
        try {
            return database.getConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get database connection", e);
            return null;
        }
    }

    /**
     * 序列化玩家背包为 Base64 字符串
     */
    public static String serializeInventory(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 Base64 字符串反序列化玩家背包
     */
    public static ItemStack[] deserializeInventory(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 检查玩家是否已注册
     */
    public boolean isRegistered(String username) {
        if (!enabled || !database.isAvailable()) return false;
        String sql = "SELECT 1 FROM " + playersTable + " WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-query-failed"), e);
            return false;
        }
    }

    /**
     * 注册玩家
     */
    public boolean register(String username, String password, String realName, String ip) {
        if (!enabled || !database.isAvailable()) return false;
        if (isRegistered(username)) return false;

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        String name = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        String sql = "INSERT INTO " + playersTable + " (username, password, salt, realname, lastip, lastlogin, regdate, regip) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, realName);
            ps.setString(5, ip);
            ps.setLong(6, 0);
            ps.setLong(7, now);
            ps.setString(8, ip);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-register-failed"), e);
            return false;
        }
    }

    /**
     * 验证密码
     */
    public boolean checkPassword(String username, String password) {
        if (!enabled || !database.isAvailable()) return false;
        String sql = "SELECT password, salt FROM " + playersTable + " WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("password");
                    String salt = rs.getString("salt");
                    return hash.equals(hashPassword(password, salt));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-check-password-failed"), e);
        }
        return false;
    }

    /**
     * 更新登录信息
     */
    public void updateLogin(String username, String ip) {
        if (!enabled || !database.isAvailable()) return;
        String sql = "UPDATE " + playersTable + " SET lastip = ?, lastlogin = ? WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, username.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-update-login-failed"), e);
        }
    }

    /**
     * 修改密码
     */
    public boolean changePassword(String username, String newPassword) {
        if (!enabled || !database.isAvailable()) return false;
        String salt = generateSalt();
        String hash = hashPassword(newPassword, salt);
        String sql = "UPDATE " + playersTable + " SET password = ?, salt = ? WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, username.toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, languageManager.getLogMessage("auth-change-password-failed"), e);
            return false;
        }
    }

    /**
     * 生成盐值（包级访问，供迁移工具使用）
     */
    String generateSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return String.format("%032x", new BigInteger(1, bytes));
    }

    /**
     * 哈希密码（包级访问，供迁移工具使用）
     */
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
     * 为迁移生成密码哈希（当原密码无法获取时使用）
     */
    public String hashPasswordForMigration(String password) {
        String salt = generateSalt();
        return hashPassword(password, salt);
    }

    public void close() {
        if (database != null) {
            database.close();
        }
    }

    // ==================== 玩家位置存储（供 WORLD 模式使用） ====================

    /**
     * 保存玩家的退出位置到数据库
     */
    public void savePlayerLocation(UUID uuid, Location location) {
        savePlayerLocation(uuid, location, null);
    }

    /**
     * 保存玩家的退出位置和游戏模式到数据库
     */
    public void savePlayerLocation(UUID uuid, Location location, GameMode gameMode) {
        savePlayerLocation(uuid, location, gameMode, null);
    }

    /**
     * 保存玩家的退出位置、游戏模式和背包到数据库
     */
    public void savePlayerLocation(UUID uuid, Location location, GameMode gameMode, ItemStack[] inventory) {
        if (!database.isAvailable() || location == null || location.getWorld() == null) {
            return;
        }
        String sql = getUpsertLocationSQL();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, location.getWorld().getName());
            ps.setDouble(3, location.getX());
            ps.setDouble(4, location.getY());
            ps.setDouble(5, location.getZ());
            ps.setFloat(6, location.getYaw());
            ps.setFloat(7, location.getPitch());
            ps.setString(8, gameMode != null ? gameMode.name() : null);
            ps.setString(9, inventory != null ? serializeInventory(inventory) : null);
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save player location for " + uuid, e);
        }
    }

    /**
     * 获取插入或更新位置的 SQL 语句（SQLite 用 INSERT OR REPLACE，MySQL 用 INSERT ... ON DUPLICATE KEY UPDATE）
     */
    private String getUpsertLocationSQL() {
        if (database instanceof MySQLDatabase) {
            return "INSERT INTO " + locationsTable + " (uuid, world, x, y, z, yaw, pitch, gamemode, inventory, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), yaw=VALUES(yaw), pitch=VALUES(pitch), gamemode=VALUES(gamemode), inventory=VALUES(inventory), updated_at=VALUES(updated_at)";
        } else {
            return "INSERT OR REPLACE INTO " + locationsTable + " (uuid, world, x, y, z, yaw, pitch, gamemode, inventory, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }
    }

    /**
     * 只更新玩家背包数据（不覆盖位置和游戏模式）
     */
    public void updatePlayerInventory(UUID uuid, ItemStack[] inventory) {
        if (!database.isAvailable()) {
            return;
        }
        String sql = "UPDATE " + locationsTable + " SET inventory = ?, updated_at = ? WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, inventory != null ? serializeInventory(inventory) : null);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update player inventory for " + uuid, e);
        }
    }

    /**
     * 从数据库获取玩家保存的背包
     */
    public ItemStack[] getPlayerInventory(UUID uuid) {
        if (!database.isAvailable()) {
            return null;
        }
        String sql = "SELECT inventory FROM " + locationsTable + " WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String inventoryData = rs.getString("inventory");
                    if (inventoryData != null && !inventoryData.isEmpty()) {
                        return deserializeInventory(inventoryData);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get player inventory for " + uuid, e);
        }
        return null;
    }

    /**
     * 从数据库获取玩家保存的退出位置
     */
    public Location getPlayerLocation(UUID uuid) {
        if (!database.isAvailable()) {
            return null;
        }
        String sql = "SELECT world, x, y, z, yaw, pitch FROM " + locationsTable + " WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String worldName = rs.getString("world");
                    World world = plugin.getServer().getWorld(worldName);
                    if (world == null) {
                        plugin.getLogger().warning("World '" + worldName + "' not found for saved location of player " + uuid);
                        return null;
                    }
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");
                    float yaw = rs.getFloat("yaw");
                    float pitch = rs.getFloat("pitch");
                    return new Location(world, x, y, z, yaw, pitch);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get player location for " + uuid, e);
        }
        return null;
    }

    /**
     * 从数据库获取玩家保存的游戏模式
     */
    public GameMode getPlayerGameMode(UUID uuid) {
        if (!database.isAvailable()) {
            return null;
        }
        String sql = "SELECT gamemode FROM " + locationsTable + " WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String modeStr = rs.getString("gamemode");
                    if (modeStr != null && !modeStr.isEmpty()) {
                        try {
                            return GameMode.valueOf(modeStr);
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("Invalid gamemode '" + modeStr + "' for player " + uuid);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get player gamemode for " + uuid, e);
        }
        return null;
    }

    /**
     * 删除玩家保存的位置记录
     */
    public void deletePlayerLocation(UUID uuid) {
        if (!database.isAvailable()) {
            return;
        }
        String sql = "DELETE FROM " + locationsTable + " WHERE uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete player location for " + uuid, e);
        }
    }

    /**
     * 获取当前使用的数据库实例
     */
    public Database getDatabase() {
        return database;
    }
}
