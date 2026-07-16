package top.mcocet.loginqueue2.listener;

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
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class QueueItemListener implements Listener {

    private final LoginQueue2 plugin;
    private final PlayerJoinListener listener;
    private final boolean autoQueue;
    private final int slot;
    private final Material material;
    private final String itemName;
    private final LanguageManager languageManager;

    private final Map<UUID, ItemStack> savedItems = new HashMap<>();

    public QueueItemListener(LoginQueue2 plugin, PlayerJoinListener listener) {
        this.plugin = plugin;
        this.listener = listener;
        this.languageManager = plugin.getLanguageManager();
        this.autoQueue = plugin.getConfig().getBoolean("queue.auto-queue", true);
        this.slot = Math.max(0, Math.min(8, plugin.getConfig().getInt("queue-item.slot", 4)));
        String matName = plugin.getConfig().getString("queue-item.material", "BEACON");
        Material parsed = Material.getMaterial(matName);
        this.material = parsed != null ? parsed : Material.BEACON;
        this.itemName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("queue-item.name", languageManager.getMessage("queue-item-name")));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (autoQueue) {
            return;
        }

        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
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

        // WORLD 模式下直接入队，不需要检查 BungeeCord 主服务器
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            listener.addPlayerToQueue(player);
            player.sendMessage(languageManager.getMessage("joined-queue"));
            return;
        }

        // UDP 优先模式下直接信任缓存，跳过实时检测
        boolean udpPreferred = plugin.getConfig().getBoolean("udp-sync.enabled", false)
                && "UDP".equalsIgnoreCase(plugin.getConfig().getString("udp-sync.priority", "BC_CHANNEL"));

        if (udpPreferred) {
            // UDP 优先：直接使用缓存状态
            if (!plugin.getMessenger().isMainServerOnline()) {
                player.sendMessage(languageManager.getMessage("main-offline"));
                return;
            }
            listener.addPlayerToQueue(player);
            player.sendMessage(languageManager.getMessage("joined-queue"));
            return;
        }

        // 默认/BC 优先模式：先检查缓存状态
        if (plugin.getMessenger().isMainServerOnline()) {
            // 缓存显示在线，直接入队
            listener.addPlayerToQueue(player);
            player.sendMessage(languageManager.getMessage("joined-queue"));
            return;
        }

        // 缓存中没有有效数据或显示离线，进行实时检测（BC 优先模式下首次连接时缓存可能为空）
        player.sendMessage(languageManager.getMessage("checking-main-server"));
        plugin.getMessenger().checkMainServerOnlineAsync(3).whenComplete((online, throwable) -> {
            SchedulerUtil.runTask(plugin, () -> {
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
        // 保存该槽位原有物品（如果不是队列物品）
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing != null && !isQueueItem(existing)) {
            savedItems.put(player.getUniqueId(), existing.clone());
        }
        player.getInventory().setItem(slot, item);
    }

    /**
     * 清除玩家的队列物品（加入游戏按钮），并恢复原有物品
     */
    public void removeQueueItem(Player player) {
        ItemStack item = player.getInventory().getItem(slot);
        if (isQueueItem(item)) {
            player.getInventory().setItem(slot, null);
        }
        // 恢复原有物品
        ItemStack saved = savedItems.remove(player.getUniqueId());
        if (saved != null) {
            player.getInventory().setItem(slot, saved);
        }
    }

    /**
     * 清除玩家物品栏中所有队列物品（扫描全部槽位）。
     * 注意：不恢复任何原有物品，因为 WORLD 模式下背包恢复由 WorldInventoryListener 统一管理。
     */
    public void removeAllQueueItems(Player player) {
        int removedCount = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isQueueItem(item)) {
                player.getInventory().setItem(i, null);
                removedCount++;
                plugin.getLogger().info("[QueueItem] removeAllQueueItems: removed queue item at slot " + i + " for player " + player.getName());
            }
        }
        // 清理 savedItems 中的记录，防止状态泄漏
        savedItems.remove(player.getUniqueId());
        plugin.getLogger().info("[QueueItem] removeAllQueueItems: removed " + removedCount + " queue items for player " + player.getName());
    }
}