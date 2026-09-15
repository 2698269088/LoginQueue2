package top.mcocet.loginqueue2online.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import top.mcocet.loginqueue2online.LoginQueue2Online;
import top.mcocet.loginqueue2online.match.Match;
import top.mcocet.loginqueue2online.match.MatchManager;
import top.mcocet.loginqueue2online.match.MatchState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 小游戏对局管理指令
 * /lq2match list|create|remove|state|players|start|end
 */
public class MatchCommand implements TabExecutor {

    private final LoginQueue2Online plugin;

    public MatchCommand(LoginQueue2Online plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("loginqueue2online.match")) {
            sender.sendMessage("§c你没有权限使用此指令。");
            return true;
        }

        MatchManager manager = plugin.getMatchManager();
        if (manager == null || !manager.isEnabled()) {
            sender.sendMessage("§c小游戏对局功能未启用（请在 config.yml 中设置 match.enabled: true）。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list":
                return handleList(sender, manager);
            case "create":
                return handleCreate(sender, manager, args);
            case "remove":
                return handleRemove(sender, manager, args);
            case "state":
                return handleState(sender, manager, args);
            case "players":
                return handlePlayers(sender, manager, args);
            case "start":
                return handleStart(sender, manager, args);
            case "end":
                return handleEnd(sender, manager, args);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage("§c未知子命令，使用 /lq2match help 查看帮助。");
                return true;
        }
    }

    private boolean handleList(CommandSender sender, MatchManager manager) {
        List<Match> matches = new ArrayList<>(manager.getMatches());
        sender.sendMessage("§a========== 对局列表 ==========");
        if (matches.isEmpty()) {
            sender.sendMessage("§e当前没有对局");
        } else {
            for (Match match : matches) {
                String stateText = match.isWaiting() ? "§e等待中" : (match.isEnded() ? "§c已结束" : "§a进行中");
                sender.sendMessage("§e" + match.getId() + " §f[" + stateText + "§f] §f人数: §e"
                        + match.getPlayers() + "§f/§e" + match.getMinPlayers() + "-" + match.getMaxPlayers()
                        + (match.getManualPlayers() != null ? " §7(手动)" : ""));
            }
        }
        sender.sendMessage("§a==============================");
        return true;
    }

    private boolean handleCreate(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /lq2match create <最小人数> [最大人数]");
            sender.sendMessage("§c    或: /lq2match create <ID> <最小人数> [最大人数]");
            return true;
        }

        String id = null;
        int min;
        int max;
        Integer first = parseInt(args[1]);
        if (first != null) {
            // 自动 ID 模式
            min = first;
            Integer second = args.length >= 3 ? parseInt(args[2]) : null;
            max = second != null ? second : min;
        } else {
            // 指定 ID 模式
            id = args[1];
            Integer second = args.length >= 3 ? parseInt(args[2]) : null;
            if (second == null) {
                sender.sendMessage("§c用法: /lq2match create <ID> <最小人数> [最大人数]");
                return true;
            }
            min = second;
            Integer third = args.length >= 4 ? parseInt(args[3]) : null;
            max = third != null ? third : min;
        }

        if (min <= 0) {
            sender.sendMessage("§c最小人数必须大于 0。");
            return true;
        }

        Match match = id == null ? manager.createMatch(min, max) : manager.createMatch(id, min, max);
        if (match == null) {
            sender.sendMessage("§c创建失败：ID 无效或已存在（仅允许字母、数字、下划线、连字符）。");
            return true;
        }
        sender.sendMessage("§a对局已创建: " + match.getId()
                + "（开局人数 " + match.getMinPlayers() + "，最大 " + match.getMaxPlayers() + "）");
        return true;
    }

    private boolean handleRemove(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /lq2match remove <ID>");
            return true;
        }
        if (!manager.removeMatch(args[1])) {
            sender.sendMessage("§c对局不存在: " + args[1]);
            return true;
        }
        sender.sendMessage("§a对局已删除: " + args[1]);
        return true;
    }

    private boolean handleState(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /lq2match state <ID> <WAITING|RUNNING|ENDED>");
            return true;
        }
        MatchState state;
        try {
            state = MatchState.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c无效状态: " + args[2] + "（可用: WAITING, RUNNING, ENDED）");
            return true;
        }
        if (!manager.setState(args[1], state)) {
            sender.sendMessage("§c对局不存在: " + args[1]);
            return true;
        }
        sender.sendMessage("§a对局 " + args[1] + " 状态已设置为: " + state.name());
        return true;
    }

    private boolean handlePlayers(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /lq2match players <ID> [数量|auto]");
            return true;
        }
        Match match = manager.getMatch(args[1]);
        if (match == null) {
            sender.sendMessage("§c对局不存在: " + args[1]);
            return true;
        }
        if (args.length < 3) {
            Integer manual = match.getManualPlayers();
            sender.sendMessage("§e对局 " + match.getId() + " 当前人数: " + match.getPlayers()
                    + (manual != null ? "（手动设置: " + manual + "）" : "（自动统计）"));
            return true;
        }
        if ("auto".equalsIgnoreCase(args[2])) {
            manager.setManualPlayers(match.getId(), null);
            sender.sendMessage("§a对局 " + match.getId() + " 已切换为自动统计人数。");
            return true;
        }
        Integer count = parseInt(args[2]);
        if (count == null || count < 0) {
            sender.sendMessage("§c无效人数: " + args[2]);
            return true;
        }
        manager.setManualPlayers(match.getId(), count);
        sender.sendMessage("§a对局 " + match.getId() + " 人数已手动设置为: " + count);
        return true;
    }

    private boolean handleStart(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /lq2match start <ID>");
            return true;
        }
        if (!manager.forceStart(args[1])) {
            sender.sendMessage("§c对局不存在或不在等待中（仅等待中的对局可开始）: " + args[1]);
            return true;
        }
        sender.sendMessage("§a对局 " + args[1] + " 已开始。");
        return true;
    }

    private boolean handleEnd(CommandSender sender, MatchManager manager, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /lq2match end <ID>");
            return true;
        }
        if (!manager.endMatch(args[1])) {
            sender.sendMessage("§c对局不存在或已结束: " + args[1]);
            return true;
        }
        sender.sendMessage("§a对局 " + args[1] + " 已结束。");
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§a========== 小游戏对局管理 ==========");
        sender.sendMessage("§e/lq2match list §f- 查看所有对局");
        sender.sendMessage("§e/lq2match create <最小人数> [最大人数] §f- 创建对局（自动 ID）");
        sender.sendMessage("§e/lq2match create <ID> <最小人数> [最大人数] §f- 创建对局（指定 ID）");
        sender.sendMessage("§e/lq2match remove <ID> §f- 删除对局");
        sender.sendMessage("§e/lq2match state <ID> <WAITING|RUNNING|ENDED> §f- 设置对局状态");
        sender.sendMessage("§e/lq2match players <ID> [数量|auto] §f- 查看/设置对局人数");
        sender.sendMessage("§e/lq2match start <ID> §f- 强制开始对局");
        sender.sendMessage("§e/lq2match end <ID> §f- 结束对局");
        sender.sendMessage("§a====================================");
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("loginqueue2online.match")) {
            return completions;
        }

        MatchManager manager = plugin.getMatchManager();
        if (args.length == 1) {
            for (String sub : Arrays.asList("list", "create", "remove", "state", "players", "start", "end", "help")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && manager != null) {
            String sub = args[0].toLowerCase();
            if ("remove".equals(sub) || "state".equals(sub) || "players".equals(sub) || "start".equals(sub) || "end".equals(sub)) {
                for (Match match : manager.getMatches()) {
                    if (match.getId().toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(match.getId());
                    }
                }
            }
        } else if (args.length == 3 && "state".equalsIgnoreCase(args[0])) {
            for (String state : Arrays.asList("WAITING", "RUNNING", "ENDED")) {
                if (state.startsWith(args[2].toUpperCase())) {
                    completions.add(state);
                }
            }
        }
        return completions;
    }
}

