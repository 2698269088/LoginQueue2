package top.mcocet.loginsequence2.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class BlockInteractListener implements Listener {

    private final boolean allowBlockInteract;
    private final boolean allowBlockPlace;
    private final boolean allowBlockBreak;

    public BlockInteractListener(boolean allowBlockInteract, boolean allowBlockPlace, boolean allowBlockBreak) {
        this.allowBlockInteract = allowBlockInteract;
        this.allowBlockPlace = allowBlockPlace;
        this.allowBlockBreak = allowBlockBreak;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (allowBlockInteract) {
            return;
        }

        // 阻止与方块的交互（点击按钮、拉杆、门等）
        if (event.getClickedBlock() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (allowBlockPlace) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (allowBlockBreak) {
            return;
        }

        event.setCancelled(true);
    }
}
