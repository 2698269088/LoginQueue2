package top.mcocet.loginsequence2.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.listener.PlayerJoinListener;
import top.mcocet.loginsequence2.util.LanguageManager;

public class JoinCommand implements CommandExecutor {

    private final LoginSequence plugin;
    private final PlayerJoinListener listener;
    private final LanguageManager languageManager;

    public JoinCommand(LoginSequence plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(languageManager.getMessage("command-player-only"));
            return true;
        }

        Player player = (Player) sender;

        if (listener.isInQueue(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("already-in-queue"));
            return true;
        }

        if (!plugin.getMessenger().isMainServerOnline()) {
            player.sendMessage(languageManager.getMessage("main-offline"));
            return true;
        }

        listener.addPlayerToQueue(player);
        player.sendMessage(languageManager.getMessage("joined-queue"));
        return true;
    }
}
