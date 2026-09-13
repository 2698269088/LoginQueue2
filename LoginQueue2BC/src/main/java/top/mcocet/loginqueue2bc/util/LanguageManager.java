package top.mcocet.loginqueue2bc.util;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import top.mcocet.loginqueue2bc.LoginQueue2BC;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final LoginQueue2BC plugin;
    private Configuration langConfig;
    private final Map<String, String> cache = new HashMap<>();

    public LanguageManager(LoginQueue2BC plugin) {
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
            try (InputStream in = plugin.getResourceAsStream("lang/" + language + ".yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, langFile.toPath());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("无法保存默认语言文件: " + e.getMessage());
            }
        }

        if (langFile.exists()) {
            try {
                langConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(langFile);
            } catch (Exception e) {
                plugin.getLogger().warning("无法加载语言文件: " + e.getMessage());
                loadDefaultLanguage();
            }
        } else {
            loadDefaultLanguage();
        }
    }

    private void loadDefaultLanguage() {
        try (InputStream defaultStream = plugin.getResourceAsStream("lang/zh_CN.yml")) {
            if (defaultStream != null) {
                langConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            } else {
                langConfig = new Configuration();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法加载默认语言文件: " + e.getMessage());
            langConfig = new Configuration();
        }
    }

    public String getMessage(String key) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String message = langConfig.getString("messages." + key, "&cMissing message: " + key);
        message = net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', message);
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

    public String getLogMessage(String key) {
        if (cache.containsKey("log." + key)) {
            return cache.get("log." + key);
        }

        String message = langConfig.getString("log-messages." + key, "Missing log message: " + key);
        cache.put("log." + key, message);
        return message;
    }

    public String getLogMessage(String key, Map<String, String> placeholders) {
        String message = getLogMessage(key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }

    public String getLogMessage(String key, String... placeholders) {
        String message = getLogMessage(key);
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
