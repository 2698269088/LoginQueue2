package top.mcocet.loginsequence2online.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA256 加密工具类
 * 用于 UDP 通信的加解密
 */
public class CryptoUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /**
     * 将字符串通过 SHA256 哈希生成 32 字节密钥
     *
     * @param input 输入字符串
     * @return 32 字节的 AES 密钥
     */
    public static byte[] sha256ToKey(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 生成随机 SHA256 密钥字符串
     *
     * @return 随机密钥字符串
     */
    public static String generateRandomKey() {
        String raw = System.currentTimeMillis() + "-" + Math.random() + "-" + System.nanoTime();
        byte[] hash = sha256ToKey(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 使用 AES 加密数据
     *
     * @param data 明文数据
     * @param key  密钥字节数组
     * @return Base64 编码的密文
     */
    public static String encrypt(String data, byte[] key) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * 使用 AES 解密数据
     *
     * @param encryptedData Base64 编码的密文
     * @param key           密钥字节数组
     * @return 明文数据
     */
    public static String decrypt(String encryptedData, byte[] key) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedData);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 使用字符串密钥加密
     *
     * @param data      明文
     * @param keyString 密钥字符串
     * @return Base64 密文
     */
    public static String encryptWithStringKey(String data, String keyString) {
        return encrypt(data, sha256ToKey(keyString));
    }

    /**
     * 使用字符串密钥解密
     *
     * @param encryptedData Base64 密文
     * @param keyString     密钥字符串
     * @return 明文
     */
    public static String decryptWithStringKey(String encryptedData, String keyString) {
        return decrypt(encryptedData, sha256ToKey(keyString));
    }
}
