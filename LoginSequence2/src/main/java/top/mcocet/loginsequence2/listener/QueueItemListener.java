package top.mcocet.loginsequence2.listener;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.mcocet.loginsequence2.LoginSequence;
import top.mcocet.loginsequence2.util.LanguageManager;

public class QueueItemListener implements Listener {

    private final LoginSequence plugin;
    private final PlayerJoinListener listener;
    private final boolean autoQueue;
    private final int slot;
    private final Material material;
    private final String itemName;
    private final LanguageManager languageManager;

    public QueueItemListener(LoginSequence plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
        this.autoQueue = plugin.getConfig().getBoolean("queue.auto-queue", true);
        this.slot = Math.max(0, Math.min(8, plugin.getConfig().getInt("queue-item.slot", 4)));
        String matName = plugin.getConfig().getString("queue-item.material", "BEACON");
        Material parsed = Material.getMaterial(matName);
        this.material = parsed != null ? parsed : Material.BEACON;
        this.itemName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("queue-item.name", "&a加入游戏"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (autoQueue) {
            return;
        }

        Player player = event.getPlayer();
        giveQueueItem(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (autoQueue) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!isQueueItem(item)) {
            return;
        }

        // 允许左键和右键点击（空气或方块）
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }

        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 左键点击空气时 Bukkit 不会触发 PlayerInteractEvent
     * 需要通过 PlayerAnimationEvent 拦截手臂摆动动作
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (autoQueue) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isQueueItem(item)) {
            return;
        }

        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 防止用队列物品攻击其他实体
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (autoQueue) {
            return;
        }

        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getDamager();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isQueueItem(item)) {
            return;
        }

        event.setCancelled(true);
        handleQueueItemClick(player);
    }

    /**
     * 防止物品被丢弃
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (autoQueue) {
            return;
        }

        if (isQueueItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * 防止物品在容器中被点击移动
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (autoQueue) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isQueueItem(current) || isQueueItem(cursor)) {
            event.setCancelled(true);
        }
    }

    /**
     * 防止物品被拖拽
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (autoQueue) {
            return;
        }

        if (isQueueItem(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private boolean isQueueItem(ItemStack item) {
        if (item == null || item.getType() != material) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !itemName.equals(meta.getDisplayName())) {
            return false;
        }

        return true;
    }

    private void handleQueueItemClick(Player player) {
        if (listener.isInQueue(player.getUniqueId())) {
            player.sendMessage(languageManager.getMessage("already-in-queue"));
            return;
        }

        // 先使用缓存状态快速拒绝（如果已知离线）
        if (!plugin.getMessenger().isMainServerOnline()) {
            player.sendMessage(languageManager.getMessage("main-offline"));
            return;
        }

        // 实时检测主服务器状态，避免依赖缓存导致BC报错
        player.sendMessage(languageManager.getMessage("checking-main-server"));
        plugin.getMessenger().checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
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
            });
        });
    }

    public void giveQueueItem(Player player) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(itemName);
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(slot, item);
    }
}
