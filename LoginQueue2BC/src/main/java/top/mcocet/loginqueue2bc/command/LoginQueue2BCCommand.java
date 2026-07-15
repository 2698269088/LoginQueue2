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
                sender.sendMessage(plugin.getLanguageManager().getMessage("config-reloaded"));
                break;
            case "debug":
                boolean newState = !plugin.isDebug();
                plugin.setDebug(newState);
                sender.sendMessage(plugin.getLanguageManager().getMessage(newState ? "debug-mode-on" : "debug-mode-off"));
                break;
            case "unban":
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /lqbc unban <IP>");
                    return;
                }
                plugin.getDatabaseManager().unbanIp(args[1]);
                sender.sendMessage(plugin.getLanguageManager().getMessage("ip-unbanned", "ip", args[1]));
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                sender.sendMessage(plugin.getLanguageManager().getMessage("unknown-subcommand"));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-header"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-reload"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-debug"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-unban"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-help"));
        sender.sendMessage(plugin.getLanguageManager().getMessage("help-footer"));
    }
}
