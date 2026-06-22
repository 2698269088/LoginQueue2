package top.mcocet.loginsequence2limbo.util;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.file.FileConfiguration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {

    private final LoginSequence2Limbo plugin;
    private FileConfiguration langConfig;
    private final Map<String, String> cache = new HashMap<>();

    public LanguageManager(LoginSequence2Limbo plugin) {
        this.plugin = plugin;
        loadLanguage();
    }

    public void loadLanguage() {
        cache.clear();
        String language = plugin.getConfigValueString("language", "zh_CN");
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File langFile = new File(langFolder, language + ".yml");
        if (!langFile.exists()) {
            plugin.saveDefaultConfig();
        }

        if (langFile.exists()) {
            try {
                langConfig = new FileConfiguration(langFile);
            } catch (IOException e) {
                e.printStackTrace();
                langConfig = new FileConfiguration((InputStream) null);
            }
        } else {
            Limbo.getInstance().getConsole().sendMessage("[LoginSequence2Limbo] 语言文件 lang/" + language + ".yml 不存在，使用内置默认语言。");
            InputStream defaultStream = getClass().getClassLoader().getResourceAsStream("lang/zh_CN.yml");
            if (defaultStream != null) {
                langConfig = new FileConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            } else {
                langConfig = new FileConfiguration((InputStream) null);
            }
        }
    }

    public String getMessage(String key) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String message = langConfig.get("messages." + key, String.class);
        if (message == null) {
            message = "&cMissing message: " + key;
        }
        message = LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
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
