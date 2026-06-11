package top.mcocet.loginsequence2.util;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final JavaPlugin plugin;
    private FileConfiguration langConfig;
    private final Map<String, String> cache = new HashMap<>();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    public void loadLanguage() {
        cache.clear();
        String language = plugin.getConfig().getString("language", "zh_CN");
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File langFile = new File(langFolder, language + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + language + ".yml", false);
        }

        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        } else {
            plugin.getLogger().warning("语言文件 lang/" + language + ".yml 不存在，使用内置默认语言。");
            InputStream defaultStream = plugin.getResource("lang/zh_CN.yml");
            if (defaultStream != null) {
                langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            } else {
                langConfig = new YamlConfiguration();
            }
        }
    }

    public String getMessage(String key) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String message = langConfig.getString("messages." + key, "&cMissing message: " + key);
        message = ChatColor.translateAlternateColorCodes('&', message);
        cache.put(key, message);
        return message;
    }

    public String getMessage(String key, Map<String, String> placeholders) {
        String message = getMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public String getMessage(String key, String... placeholders) {
        String message = getMessage(key);
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("占位符参数必须为键值对形式");
        }
        for (int i = 0; i < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }

    public void reload() {
        loadLanguage();
    }
}
