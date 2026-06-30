package top.mcocet.loginqueue2vc.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import top.mcocet.loginqueue2vc.LoginQueue2VC;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LoginQueue2VCCommand implements SimpleCommand {

    private final LoginQueue2VC plugin;

    public LoginQueue2VCCommand(LoginQueue2VC plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(invocation.source());
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                plugin.reloadConfig();
                invocation.source().sendMessage(Component.text("LoginQueue2VC 配置文件已重载。").color(NamedTextColor.GREEN));
                break;
            case "debug":
                boolean newState = !plugin.isDebug();
                plugin.setDebug(newState);
                invocation.source().sendMessage(Component.text(newState ? "调试模式已开启。" : "调试模式已关闭。")
                        .color(newState ? NamedTextColor.GREEN : NamedTextColor.RED));
                break;
            case "unban":
                if (args.length < 2) {
                    invocation.source().sendMessage(Component.text("用法: /lsvc unban <IP>").color(NamedTextColor.RED));
                    return;
                }
                plugin.getDatabaseManager().unbanIp(args[1]);
                invocation.source().sendMessage(Component.text("已解封IP: " + args[1]).color(NamedTextColor.GREEN));
                break;
            case "help":
                sendHelp(invocation.source());
                break;
            default:
                invocation.source().sendMessage(Component.text("未知子命令，使用 /lsvc help 查看帮助。").color(NamedTextColor.RED));
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("========== LoginQueue2VC 帮助 ==========").color(NamedTextColor.GREEN));
        source.sendMessage(Component.text("/lsvc reload - 重载配置文件").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/lsvc debug - 切换调试模式").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/lsvc unban <IP> - 解封指定IP").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/lsvc help - 显示此帮助").color(NamedTextColor.YELLOW));
        source.sendMessage(Component.text("==========================================").color(NamedTextColor.GREEN));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("reload", "debug", "unban", "help");
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(suggest(invocation));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("loginqueue2vc.admin");
    }
}
