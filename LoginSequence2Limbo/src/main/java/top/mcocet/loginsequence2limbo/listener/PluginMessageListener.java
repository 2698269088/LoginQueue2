package top.mcocet.loginsequence2limbo.listener;

import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PluginMessageEvent;
import top.mcocet.loginsequence2limbo.bungee.BungeeMessenger;

public class PluginMessageListener implements Listener {

    private final BungeeMessenger messenger;

    public PluginMessageListener(BungeeMessenger messenger) {
        this.messenger = messenger;
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        messenger.onPluginMessageReceived(event);
    }
}
