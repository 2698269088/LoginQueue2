package top.mcocet.loginqueue2.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.util.SchedulerUtil;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * WORLD 模式下玩家背包管理监听器
 *
 * 核心设计：内存快照
 * 1. 玩家进入登录世界时：把当前背包（主物品栏+护甲+额外栏）复制一份存到内存 Map，然后清空背包，发放队列物品。
 * 2. 玩家进入主世界时：从内存 Map 取出备份，写回玩家背包，然后删除备份，清除队列物品。
 * 3. 玩家退出时：如果在登录世界，从内存恢复背包后再退出（防止数据丢失）。
 */
public class WorldInventoryListener implements Listener {

    private final LoginQueue2 plugin;

    /**
     * 标记玩家当前是否处于"背包已清空"状态（在登录世界中）
     */
    private final Set<UUID> inventoryCleared = new HashSet<>();

    /**
     * 内存中保存的玩家原始背包快照
     * Key: 玩家 UUID
     * Value: 原始背包内容（深拷贝，包含主物品栏+护甲+额外栏）
     */
    private final Map<UUID, SavedInventory> savedInventories = new HashMap<>();

    public WorldInventoryListener(LoginQueue2 plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家加入时，如果在 WORLD 模式下，立即保存背包（玩家还在主世界）
     * 然后传送到登录世界，清空背包，发放队列物品
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null || !lwm.isWorldMode() || !lwm.isReady()) {
            plugin.getLogger().info("[WorldInventory] onPlayerJoin: skipped - world mode not ready");
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("[WorldInventory] onPlayerJoin: player=" + player.getName() + ", world=" + player.getWorld().getName());

        // 立即保存原始背包（玩家还在主世界，背包是完整的）
        if (!savedInventories.containsKey(uuid)) {
            SavedInventory snapshot = SavedInventory.fromPlayer(player);
            savedInventories.put(uuid, snapshot);
            plugin.getLogger().info("[WorldInventory] onPlayerJoin: saved original inventory for player " + player.getName()
                    + ", contents=" + player.getInventory().getContents().length
                    + ", armor=" + player.getInventory().getArmorContents().length
                    + ", extra=" + player.getInventory().getExtraContents().length);
        }

        SchedulerUtil.runPlayerTaskLater(player, plugin, () -> {
            if (!player.isOnline()) {
                plugin.getLogger().info("[WorldInventory] onPlayerJoin delayed: player offline, abort");
                return;
            }
            boolean inLogin = lwm.isInLoginWorld(player);
            plugin.getLogger().info("[WorldInventory] onPlayerJoin delayed: player=" + player.getName() + ", inLoginWorld=" + inLogin);
            if (inLogin) {
                enterLoginWorld(player);
            }
        }, 5L);
    }

    /**
     * 玩家切换世界时处理背包
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null || !lwm.isWorldMode() || !lwm.isReady()) {
            return;
        }

        Player player = event.getPlayer();
        World fromWorld = event.getFrom();
        World toWorld = player.getWorld();
        World loginWorld = lwm.getLoginWorld();

        plugin.getLogger().info("[WorldInventory] onPlayerChangedWorld: player=" + player.getName()
                + ", from=" + fromWorld.getName() + ", to=" + toWorld.getName()
                + ", loginWorld=" + (loginWorld != null ? loginWorld.getName() : "null"));

        if (loginWorld == null) {
            plugin.getLogger().warning("[WorldInventory] onPlayerChangedWorld: loginWorld is null!");
            return;
        }

        // 进入登录世界
        if (loginWorld.equals(toWorld) && !loginWorld.equals(fromWorld)) {
            plugin.getLogger().info("[WorldInventory] onPlayerChangedWorld: ENTER login world");
            enterLoginWorld(player);
            return;
        }

        // 从登录世界离开进入主世界
        if (loginWorld.equals(fromWorld) && !loginWorld.equals(toWorld)) {
            plugin.getLogger().info("[WorldInventory] onPlayerChangedWorld: LEAVE login world");
            leaveLoginWorld(player);
        }
    }

    /**
     * 玩家重生时，如果未放行且重生点在主世界，强制在登录世界重生
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null || !lwm.isWorldMode() || !lwm.isReady()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 已放行的玩家允许在主世界重生
        PlayerJoinListener playerJoinListener = plugin.getPlayerJoinListener();
        if (playerJoinListener != null && playerJoinListener.getAllowedPlayers().contains(uuid)) {
            plugin.getLogger().info("[WorldInventory] onPlayerRespawn: player=" + player.getName() + " is allowed, clearing inventoryCleared");
            inventoryCleared.remove(uuid);
            return;
        }

        // 未放行玩家：强制在登录世界重生
        Location loginSpawn = lwm.getLoginSpawnLocation();
        if (loginSpawn != null) {
            event.setRespawnLocation(loginSpawn);
            plugin.getLogger().info("[WorldInventory] onPlayerRespawn: forced player " + player.getName() + " to respawn in login world");
        }

        // 延迟处理：重生后背包是空的，清空标记并重新进入登录世界逻辑
        SchedulerUtil.runPlayerTaskLater(player, plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.getLogger().info("[WorldInventory] onPlayerRespawn delayed: player=" + player.getName() + ", clearing state and re-entering");
            inventoryCleared.remove(uuid);
            if (lwm.isInLoginWorld(player)) {
                enterLoginWorld(player);
            }
        }, 2L);
    }

    /**
     * 玩家退出时，如果在登录世界且背包被清空，先恢复原始背包再退出
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null || !lwm.isWorldMode() || !lwm.isReady()) {
            return;
        }

        plugin.getLogger().info("[WorldInventory] onPlayerQuit: player=" + player.getName()
                + ", inventoryCleared=" + inventoryCleared.contains(uuid)
                + ", hasSaved=" + savedInventories.containsKey(uuid));

        // 如果玩家在登录世界且背包被清空，恢复原始背包
        if (inventoryCleared.contains(uuid)) {
            SavedInventory saved = savedInventories.remove(uuid);
            if (saved != null) {
                saved.restoreTo(player);
                plugin.getLogger().info("[WorldInventory] onPlayerQuit: restored original inventory for player " + player.getName());
            } else {
                plugin.getLogger().warning("[WorldInventory] onPlayerQuit: no saved inventory found for player " + player.getName());
            }
        }

        // 清理状态
        inventoryCleared.remove(uuid);
        savedInventories.remove(uuid);
    }

    /**
     * 检查并处理主世界中的未授权玩家
     */
    public void checkUnauthorizedPlayersInMainWorld() {
        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null || !lwm.isWorldMode() || !lwm.isReady()) {
            return;
        }

        World loginWorld = lwm.getLoginWorld();
        if (loginWorld == null) {
            return;
        }

        PlayerJoinListener playerJoinListener = plugin.getPlayerJoinListener();
        if (playerJoinListener == null) {
            return;
        }

        Set<UUID> allowedPlayers = playerJoinListener.getAllowedPlayers();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (allowedPlayers.contains(uuid)) {
                continue;
            }

            if (playerJoinListener.isInQueue(uuid) && !lwm.isInLoginWorld(player)) {
                teleportBackToLoginWorld(player, "in queue but not in login world");
                continue;
            }

            if (!playerJoinListener.isInQueue(uuid) && !lwm.isInLoginWorld(player)) {
                teleportBackToLoginWorld(player, "not allowed and not in login world");
            }
        }
    }

    /**
     * 将玩家传送回登录世界
     */
    private void teleportBackToLoginWorld(Player player, String reason) {
        LoginWorldManager lwm = plugin.getLoginWorldManager();
        if (lwm == null) {
            return;
        }

        Location loginSpawn = lwm.getLoginSpawnLocation();
        if (loginSpawn == null) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!inventoryCleared.contains(uuid)) {
            enterLoginWorld(player);
        }

        SchedulerUtil.teleport(player, loginSpawn, () -> {
            plugin.getLogger().info("[WorldInventory] Teleported player " + player.getName() + " back to login world: " + reason);
        });
    }

    /**
     * 玩家进入登录世界：保存背包快照到内存，清空背包，发放队列物品
     */
    private void enterLoginWorld(Player player) {
        UUID uuid = player.getUniqueId();

        plugin.getLogger().info("[WorldInventory] enterLoginWorld: player=" + player.getName()
                + ", inventoryCleared=" + inventoryCleared.contains(uuid)
                + ", hasSaved=" + savedInventories.containsKey(uuid));

        if (inventoryCleared.contains(uuid)) {
            plugin.getLogger().info("[WorldInventory] enterLoginWorld: already cleared, skipping");
            return;
        }

        // 如果内存中已有快照（重新登录/重生），不要覆盖，直接清空当前背包
        if (!savedInventories.containsKey(uuid)) {
            // 首次进入：深拷贝当前完整背包到内存
            SavedInventory snapshot = SavedInventory.fromPlayer(player);
            savedInventories.put(uuid, snapshot);
            plugin.getLogger().info("[WorldInventory] enterLoginWorld: saved inventory to memory for player " + player.getName()
                    + ", contents=" + player.getInventory().getContents().length
                    + ", armor=" + player.getInventory().getArmorContents().length
                    + ", extra=" + player.getInventory().getExtraContents().length);
        } else {
            plugin.getLogger().info("[WorldInventory] enterLoginWorld: already has saved inventory, preserving");
        }

        // 清空玩家背包
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setExtraContents(null);
        plugin.getLogger().info("[WorldInventory] enterLoginWorld: cleared inventory for player " + player.getName());

        // 标记背包已清空
        inventoryCleared.add(uuid);

        // 发放队列物品（加入游戏按钮）
        QueueItemListener queueItemListener = plugin.getQueueItemListener();
        if (queueItemListener != null) {
            queueItemListener.giveQueueItem(player);
            plugin.getLogger().info("[WorldInventory] enterLoginWorld: gave queue item to player " + player.getName());
        }

        plugin.getLogger().info("[WorldInventory] enterLoginWorld: DONE for player " + player.getName());
    }

    /**
     * 玩家离开登录世界：从内存恢复背包，清除队列物品，删除快照
     */
    private void leaveLoginWorld(Player player) {
        UUID uuid = player.getUniqueId();

        plugin.getLogger().info("[WorldInventory] leaveLoginWorld: player=" + player.getName()
                + ", inventoryCleared=" + inventoryCleared.contains(uuid)
                + ", hasSaved=" + savedInventories.containsKey(uuid));

        // 从内存恢复原始背包
        SavedInventory saved = savedInventories.remove(uuid);
        if (saved != null) {
            saved.restoreTo(player);
            plugin.getLogger().info("[WorldInventory] leaveLoginWorld: restored inventory for player " + player.getName()
                    + ", contents=" + player.getInventory().getContents().length
                    + ", armor=" + player.getInventory().getArmorContents().length
                    + ", extra=" + player.getInventory().getExtraContents().length);
        } else {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setExtraContents(null);
            plugin.getLogger().warning("[WorldInventory] leaveLoginWorld: NO saved inventory for player " + player.getName() + ", clearing everything");
        }

        // 清除队列物品（在恢复背包后执行，确保队列物品被彻底移除）
        QueueItemListener queueItemListener = plugin.getQueueItemListener();
        if (queueItemListener != null) {
            queueItemListener.removeAllQueueItems(player);
            plugin.getLogger().info("[WorldInventory] leaveLoginWorld: removed queue items for player " + player.getName());
        }

        // 清除状态标记
        inventoryCleared.remove(uuid);
        plugin.getLogger().info("[WorldInventory] leaveLoginWorld: DONE for player " + player.getName());
    }

    /**
     * 检查玩家背包是否已被清空（在登录世界）
     */
    public boolean isInventoryCleared(UUID uuid) {
        return inventoryCleared.contains(uuid);
    }

    /**
     * 传送前移除队列物品（在登录世界执行）
     * 避免恢复背包时队列物品覆盖原有物品
     */
    public void removeQueueItemsBeforeTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("[WorldInventory] removeQueueItemsBeforeTeleport: player=" + player.getName()
                + ", inventoryCleared=" + inventoryCleared.contains(uuid));

        // 只有背包被清空过（在登录世界）才需要移除队列物品
        if (!inventoryCleared.contains(uuid)) {
            plugin.getLogger().info("[WorldInventory] removeQueueItemsBeforeTeleport: inventory not cleared, skipping");
            return;
        }

        QueueItemListener queueItemListener = plugin.getQueueItemListener();
        if (queueItemListener != null) {
            queueItemListener.removeAllQueueItems(player);
            plugin.getLogger().info("[WorldInventory] removeQueueItemsBeforeTeleport: removed queue items for player " + player.getName());
        }
    }

    /**
     * 从登录世界恢复玩家背包（供 LoginWorldManager 在传送回调中直接调用）
     * 不依赖 PlayerChangedWorldEvent，确保在 Folia 异步传送后也能正确恢复
     */
    public void restoreInventoryFromLoginWorld(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("[WorldInventory] restoreInventoryFromLoginWorld: player=" + player.getName()
                + ", inventoryCleared=" + inventoryCleared.contains(uuid)
                + ", hasSaved=" + savedInventories.containsKey(uuid));

        // 只有背包被清空过（在登录世界）才需要恢复
        if (!inventoryCleared.contains(uuid)) {
            plugin.getLogger().info("[WorldInventory] restoreInventoryFromLoginWorld: inventory not cleared, skipping");
            return;
        }

        // 从内存恢复原始背包
        SavedInventory saved = savedInventories.remove(uuid);
        if (saved != null) {
            saved.restoreTo(player);
            plugin.getLogger().info("[WorldInventory] restoreInventoryFromLoginWorld: restored inventory for player " + player.getName()
                    + ", contents=" + player.getInventory().getContents().length
                    + ", armor=" + player.getInventory().getArmorContents().length
                    + ", extra=" + player.getInventory().getExtraContents().length);
            // 调试：检查恢复后槽位 4 的内容
            ItemStack slot4 = player.getInventory().getItem(4);
            plugin.getLogger().info("[WorldInventory] restoreInventoryFromLoginWorld: slot4 after restore=" + (slot4 != null ? slot4.getType().name() : "null"));
        } else {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setExtraContents(null);
            plugin.getLogger().warning("[WorldInventory] restoreInventoryFromLoginWorld: NO saved inventory for player " + player.getName() + ", clearing everything");
        }

        // 清除状态标记
        inventoryCleared.remove(uuid);
        plugin.getLogger().info("[WorldInventory] restoreInventoryFromLoginWorld: DONE for player " + player.getName());
    }

    /**
     * 保存的完整背包数据结构（包含主物品栏、护甲、额外栏）
     */
    private static class SavedInventory {
        private final ItemStack[] contents;
        private final ItemStack[] armorContents;
        private final ItemStack[] extraContents;

        private SavedInventory(ItemStack[] contents, ItemStack[] armorContents, ItemStack[] extraContents) {
            this.contents = deepCopy(contents);
            this.armorContents = deepCopy(armorContents);
            this.extraContents = deepCopy(extraContents);
        }

        static SavedInventory fromPlayer(Player player) {
            ItemStack[] contents = player.getInventory().getContents();
            ItemStack[] armor = player.getInventory().getArmorContents();
            ItemStack[] extra = player.getInventory().getExtraContents();
            // 调试：记录保存时的数组长度和槽位 4 的内容
            player.getServer().getLogger().info("[WorldInventory] SavedInventory.fromPlayer: contentsLen=" + contents.length
                    + ", armorLen=" + armor.length
                    + ", extraLen=" + extra.length
                    + ", slot4=" + (contents.length > 4 && contents[4] != null ? contents[4].getType().name() : "null"));
            return new SavedInventory(contents, armor, extra);
        }

        void restoreTo(Player player) {
            ItemStack[] contentsCopy = deepCopy(contents);
            ItemStack[] armorCopy = deepCopy(armorContents);
            ItemStack[] extraCopy = deepCopy(extraContents);
            // 调试：记录恢复前的数组长度和槽位 4 的内容
            player.getServer().getLogger().info("[WorldInventory] SavedInventory.restoreTo: before restore contentsLen=" + contentsCopy.length
                    + ", slot4=" + (contentsCopy.length > 4 && contentsCopy[4] != null ? contentsCopy[4].getType().name() : "null"));
            // 只设置主物品栏前36槽，避免Paper中setContents()不接受43槽的问题
            ItemStack[] mainContents = new ItemStack[36];
            for (int i = 0; i < 36 && i < contentsCopy.length; i++) {
                mainContents[i] = contentsCopy[i];
            }
            player.getInventory().setContents(mainContents);
            player.getInventory().setArmorContents(armorCopy);
            player.getInventory().setExtraContents(extraCopy);
            // 调试：记录恢复后的槽位 4 内容
            ItemStack[] afterContents = player.getInventory().getContents();
            player.getServer().getLogger().info("[WorldInventory] SavedInventory.restoreTo: after restore contentsLen=" + afterContents.length
                    + ", slot4=" + (afterContents.length > 4 && afterContents[4] != null ? afterContents[4].getType().name() : "null"));
        }

        private static ItemStack[] deepCopy(ItemStack[] items) {
            if (items == null) {
                return new ItemStack[0];
            }
            ItemStack[] copy = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                copy[i] = items[i] != null ? items[i].clone() : null;
            }
            return copy;
        }
    }
}
