package top.mcocet.loginsequence2limbo.listener;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.EventPriority;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.inventory.InventoryClickEvent;
import com.loohp.limbo.events.inventory.InventoryDragEvent;
import com.loohp.limbo.events.player.PlayerInteractEvent;
import com.loohp.limbo.events.player.PlayerJoinEvent;
import com.loohp.limbo.inventory.ItemStack;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.registry.DataComponentType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import top.mcocet.loginsequence2limbo.LoginSequence2Limbo;
import top.mcocet.loginsequence2limbo.bungee.BungeeMessenger;
import top.mcocet.loginsequence2limbo.util.LanguageManager;

public class QueueItemListener implements Listener {

    private final LoginSequence2Limbo plugin;
    private final PlayerJoinListener listener;
    private final boolean autoQueue;
    private final int slot;
    private final Key material;
    private final Component itemName;
    private final LanguageManager languageManager;

    public QueueItemListener(LoginSequence2Limbo plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
        this.autoQueue = plugin.getConfigValueBoolean("queue.auto-queue", true);
        this.slot = Math.max(0, Math.min(8, plugin.getConfigValueInt("queue-item.slot", 4)));
        String matName = plugin.getConfigValueString("queue-item.material", "minecraft:beacon");
        if (!matName.contains(":")) {
            matName = "minecraft:" + matName.toLowerCase();
        }
        this.material = Key.key(matName);
        String nameStr = plugin.getConfigValueString("queue-item.name", "&a加入游戏");
        this.itemName = LegacyComponentSerializer.legacyAmpersand().deserialize(nameStr);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (autoQueue) {
            return;
        }

        Player player = event.getPlayer();
        giveQueueItem(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (autoQueue) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isQueueItem(item)) {
            return;
        }

        // 允许左键和右键点击（空气或方块），但排除物理触发
        if (event.getAction() == PlayerInteractEvent.Action.PHYSICAL) {
            return;
        }

        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 防止物品在容器中被点击移动
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (autoQueue) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack carried = event.getCarriedItem();

        if (isQueueItem(current) || isQueueItem(carried)) {
            event.setCancelled(true);
        }
    }

    /**
     * 防止物品被拖拽
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (autoQueue) {
            return;
        }

        // InventoryDragEvent 没有 getOldCursor，通过 view 获取
        ItemStack cursor = event.getView().getCarriedItem();
        if (isQueueItem(cursor)) {
            event.setCancelled(true);
        }
    }

    private boolean isQueueItem(ItemStack item) {
        if (item == null || item.type().equals(ItemStack.AIR.type())) {
            return false;
        }

        if (!item.type().equals(material)) {
            return false;
        }

        Component displayName = item.displayName();
        if (displayName == null || !displayName.equals(itemName)) {
            return false;
        }

        return true;
    }

    private void handleQueueItemClick(Player player) {
        if (listener.isInQueue(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("already-in-queue"));
            return;
        }

        // 检查是否有任何主服务器在线（基于缓存），与 /join 命令逻辑保持一致
        boolean anyOnline = false;
        for (BungeeMessenger.ServerStatus status : plugin.getMessenger().getAllServerStatus().values()) {
            if (status.isOnline()) {
                anyOnline = true;
                break;
            }
        }

        if (anyOnline) {
            // 缓存中有在线服务器，直接入队
            listener.addPlayerToQueue(player);
            player.sendMessage(languageManager.getMessage("joined-queue"));
            return;
        }

        // 缓存中没有在线服务器，进行实时检测
        player.sendMessage(languageManager.getMessage("checking-main-server"));
        plugin.getMessenger().checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
            Limbo.getInstance().getScheduler().runTask(plugin, new com.loohp.limbo.scheduler.LimboTask() {
                @Override
                public void run() {
                    if (throwable != null || !online) {
                        player.sendMessage(languageManager.getMessage("main-offline"));
                        return;
                    }

                    // 再次检查是否已在队列中（异步期间可能状态变化）
                    if (listener.isInQueue(player.getUniqueId())) {
                        return;
                    }

                    listener.addPlayerToQueue(player);
                    player.sendMessage(languageManager.getMessage("joined-queue"));
                }
            });
        });
    }

    public void giveQueueItem(Player player) {
        ItemStack item = new ItemStack(material, 1);
        item = item.displayName(itemName);
        // Limbo 的 PlayerInventory.setItem 内部已处理槽位映射
        // 主手栏直接使用 0-8 即可
        player.getInventory().setItem(slot, item);
        player.updateInventory();
    }
}
