package top.mcocet.loginsequence2limbo.auth;

import com.loohp.limbo.Limbo;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * 认证数据管理器 - 使用文件存储玩家账号数据（Limbo 无 SQLite 驱动）
 */
public class AuthManager {

    private final LoginSequence2Limbo plugin;
    private final boolean enabled;
    private final File dataFile;
    private final Map<String, AuthData> authCache = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthManager(LoginSequence2Limbo plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfigValueBoolean("auth.enabled", false);
        this.dataFile = new File(plugin.getDataFolder(), "auth.dat");
        if (enabled) {
            loadData();
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) {
                authCache.putAll((Map<String, AuthData>) obj);
            }
            Limbo.getInstance().getConsole().sendMessage("[Auth] 已加载 " + authCache.size() + " 条玩家数据");
        } catch (Exception e) {
            Limbo.getInstance().getConsole().sendMessage("[Auth] 加载数据失败: " + e.getMessage());
        }
    }

    private synchronized void saveData() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataFile))) {
                oos.writeObject(new HashMap<>(authCache));
            }
        } catch (IOException e) {
            Limbo.getInstance().getConsole().sendMessage("[Auth] 保存数据失败: " + e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRegistered(String username) {
        if (!enabled) return false;
        return authCache.containsKey(username.toLowerCase());
    }

    public boolean register(String username, String password, String realName, String ip) {
        if (!enabled) return false;
        String name = username.toLowerCase();
        if (authCache.containsKey(name)) return false;

        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        AuthData data = new AuthData(name, hash, salt, realName, ip, System.currentTimeMillis(), ip);
        authCache.put(name, data);
        saveData();
        return true;
    }

    public boolean checkPassword(String username, String password) {
        if (!enabled) return false;
        AuthData data = authCache.get(username.toLowerCase());
        if (data == null) return false;
        return data.hash.equals(hashPassword(password, data.salt));
    }

    public void updateLogin(String username, String ip) {
        if (!enabled) return;
        AuthData data = authCache.get(username.toLowerCase());
        if (data != null) {
            data.lastIp = ip;
            data.lastLogin = System.currentTimeMillis();
            saveData();
        }
    }

    public boolean changePassword(String username, String newPassword) {
        if (!enabled) return false;
        AuthData data = authCache.get(username.toLowerCase());
        if (data == null) return false;
        data.salt = generateSalt();
        data.hash = hashPassword(newPassword, data.salt);
        saveData();
        return true;
    }

    private String generateSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return String.format("%032x", new BigInteger(1, bytes));
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((password + salt).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return String.format("%064x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static class AuthData implements Serializable {
        private static final long serialVersionUID = 1L;
        String username;
        String hash;
        String salt;
        String realName;
        String lastIp;
        long lastLogin;
        long regDate;
        String regIp;

        AuthData(String username, String hash, String salt, String realName,
                 String lastIp, long regDate, String regIp) {
            this.username = username;
            this.hash = hash;
            this.salt = salt;
            this.realName = realName;
            this.lastIp = lastIp;
            this.lastLogin = 0;
            this.regDate = regDate;
            this.regIp = regIp;
        }
    }
}
