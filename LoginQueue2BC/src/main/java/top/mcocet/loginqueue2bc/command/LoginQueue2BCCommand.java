package top.mcocet.loginqueue2bc.command;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import top.mcocet.loginqueue2bc.LoginQueue2BC;

public class LoginQueue2BCCommand extends Command {

    private final LoginQueue2BC plugin;

    public LoginQueue2BCCommand(LoginQueue2BC plugin) {
        super("lqbc", "loginqueue2bc.admin", "loginqueue2bc");
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage("§aLoginQueue2BC 配置文件已重载。");
                break;
            case "debug":
                boolean newState = !plugin.isDebug();
                plugin.setDebug(newState);
                sender.sendMessage(newState ? "§a调试模式已开启。" : "§c调试模式已关闭。");
                break;
            case "unban":
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /lqbc unban <IP>");
                    return;
                }
                plugin.getDatabaseManager().unbanIp(args[1]);
                sender.sendMessage("§a已解封IP: " + args[1]);
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                sender.sendMessage("§c未知子命令，使用 /lqbc help 查看帮助。");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§a========== LoginQueue2BC 帮助 ==========");
        sender.sendMessage("§e/lqbc reload&f - 重载配置文件");
        sender.sendMessage("§e/lqbc debug&f - 切换调试模式");
        sender.sendMessage("§e/lqbc unban <IP>&f - 解封指定IP");
        sender.sendMessage("§e/lqbc help&f - 显示此帮助");
        sender.sendMessage("§a==========================================");
    }
}
