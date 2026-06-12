package top.mcocet.loginsequence2bc.command;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import top.mcocet.loginsequence2bc.LoginSequence2BC;

public class LoginSequenceBCCommand extends Command {

    private final LoginSequence2BC plugin;

    public LoginSequenceBCCommand(LoginSequence2BC plugin) {
        super("lsbc", "loginsequence2bc.admin", "loginsequencebc");
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
                sender.sendMessage("§aLoginSequence2BC 配置文件已重载。");
                break;
            case "debug":
                boolean newState = !plugin.isDebug();
                plugin.setDebug(newState);
                sender.sendMessage(newState ? "§a调试模式已开启。" : "§c调试模式已关闭。");
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                sender.sendMessage("§c未知子命令，使用 /lsbc help 查看帮助。");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§a========== LoginSequence2BC 帮助 ==========");
        sender.sendMessage("§e/lsbc reload&f - 重载配置文件");
        sender.sendMessage("§e/lsbc debug&f - 切换调试模式");
        sender.sendMessage("§e/lsbc help&f - 显示此帮助");
        sender.sendMessage("§a==========================================");
    }
}
