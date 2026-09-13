package top.mcocet.loginqueue2limbo.queue;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.player.Player;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 增强的优先队列管理器 (Limbo 版本)
 * 支持多种优先级匹配规则：permission, name, uuid, regex, group
 * 同优先级按入队时间先后排序（FIFO）
 */
public class PriorityManager {

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private List<PriorityRule> priorityRules;
    private int defaultPriority;
    private boolean priorityEnabled;

    public PriorityManager(LoginQueue2Limbo plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        reload();
    }

    /**
     * 从配置重新加载优先级规则
     */
    public void reload() {
        this.priorityEnabled = plugin.getConfigValueBoolean("queue.priority-enabled", true);
        this.defaultPriority = plugin.getConfigValueInt("queue.default-priority", 0);
        this.priorityRules = new ArrayList<>();

        List<String> rawRules = plugin.getConfigValueStringList("queue.priority");
        int ruleIndex = 0;
        for (String rule : rawRules) {
            ruleIndex++;
            if (rule == null || rule.isEmpty()) continue;

            String[] parts = rule.split(":", 3);
            if (parts.length < 2) {
                Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + languageManager.getLogMessage("priority-rule-format-error", "index", String.valueOf(ruleIndex), "rule", rule));
                continue;
            }

            String type = parts[0].toLowerCase();
            String value = parts[1];
            int weight = 100 - ruleIndex;

            if (parts.length >= 3) {
                try {
                    weight = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + languageManager.getLogMessage("priority-weight-format-error", "weight", parts[2]));
                }
            }

            PriorityRule parsedRule = parseRule(type, value, weight);
            if (parsedRule != null) {
                priorityRules.add(parsedRule);
            }
        }

        Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + languageManager.getLogMessage("priority-loaded", "count", String.valueOf(priorityRules.size()), "default", String.valueOf(defaultPriority)));
    }

    private PriorityRule parseRule(String type, String value, int weight) {
        switch (type) {
            case "permission":
                return new PermissionRule(value, weight);
            case "name":
                return new NameRule(value, weight);
            case "uuid":
                return new UuidRule(value, weight);
            case "regex":
                return new RegexRule(value, weight);
            case "group":
                return new GroupRule(value, weight);
            default:
                Limbo.getInstance().getConsole().sendMessage(languageManager.getLogMessage("plugin-prefix") + " " + languageManager.getLogMessage("priority-unknown-type", "type", type));
                return null;
        }
    }

    /**
     * 计算玩家的优先级权重
     */
    public int calculatePriority(Player player) {
        if (!priorityEnabled) {
            return defaultPriority;
        }

        int highestPriority = defaultPriority;
        for (PriorityRule rule : priorityRules) {
            if (rule.matches(player)) {
                highestPriority = Math.max(highestPriority, rule.getWeight());
            }
        }
        return highestPriority;
    }

    /**
     * 获取玩家匹配的所有优先级规则描述
     */
    public List<String> getMatchedRules(Player player) {
        List<String> matched = new ArrayList<>();
        for (PriorityRule rule : priorityRules) {
            if (rule.matches(player)) {
                matched.add(rule.getDescription());
            }
        }
        return matched;
    }

    public boolean isPriorityEnabled() {
        return priorityEnabled;
    }

    public int getDefaultPriority() {
        return defaultPriority;
    }

    public List<PriorityRule> getPriorityRules() {
        return new ArrayList<>(priorityRules);
    }

    // ==================== 规则接口与实现 ====================

    public interface PriorityRule {
        boolean matches(Player player);
        int getWeight();
        String getDescription();
    }

    private class PermissionRule implements PriorityRule {
        private final String permission;
        private final int weight;

        PermissionRule(String permission, int weight) {
            this.permission = permission;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.hasPermission(permission);
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public String getDescription() {
            return languageManager.getMessage("priority-desc-permission", "permission", permission, "weight", String.valueOf(weight));
        }
    }

    private class NameRule implements PriorityRule {
        private final String name;
        private final int weight;

        NameRule(String name, int weight) {
            this.name = name;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.getName().equalsIgnoreCase(name);
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public String getDescription() {
            return languageManager.getMessage("priority-desc-name", "name", name, "weight", String.valueOf(weight));
        }
    }

    private class UuidRule implements PriorityRule {
        private final UUID uuid;
        private final int weight;

        UuidRule(String uuidStr, int weight) {
            this.uuid = UUID.fromString(uuidStr);
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.getUniqueId().equals(uuid);
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public String getDescription() {
            return languageManager.getMessage("priority-desc-uuid", "uuid", uuid.toString(), "weight", String.valueOf(weight));
        }
    }

    private class RegexRule implements PriorityRule {
        private final Pattern pattern;
        private final int weight;
        private final String rawPattern;

        RegexRule(String regex, int weight) {
            this.rawPattern = regex;
            Pattern p = null;
            try {
                p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                // 会在外部处理
            }
            this.pattern = p;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return pattern != null && pattern.matcher(player.getName()).matches();
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public String getDescription() {
            return languageManager.getMessage("priority-desc-regex", "pattern", rawPattern, "weight", String.valueOf(weight));
        }
    }

    private class GroupRule implements PriorityRule {
        private final String group;
        private final int weight;

        GroupRule(String group, int weight) {
            this.group = group;
            this.weight = weight;
        }

        @Override
        public boolean matches(Player player) {
            return player.hasPermission("group." + group);
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public String getDescription() {
            return languageManager.getMessage("priority-desc-group", "group", group, "weight", String.valueOf(weight));
        }
    }
}
