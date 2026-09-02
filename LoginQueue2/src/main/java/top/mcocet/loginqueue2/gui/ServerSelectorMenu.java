package top.mcocet.loginqueue2.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.mcocet.loginqueue2.LoginQueue2;
import top.mcocet.loginqueue2.bungee.BungeeMessenger;
import top.mcocet.loginqueue2.util.LanguageManager;
import top.mcocet.loginqueue2.util.SchedulerUtil;
import top.mcocet.loginqueue2.world.LoginWorldManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UDP 多主服务器选择菜单
 * 当 udp-sync.server-selector.enabled 为 true 时，
 * 玩家点击“加入游戏”按钮或使用 /join 命令将弹出此菜单，
 * 由玩家手动选择要加入的服务器。
 */
public class ServerSelectorMenu implements Listener {

    private static final String LORE_ID_PREFIX = "ID:";

    private final LoginQueue2 plugin;
    private final LanguageManager languageManager;
    private String title;
    private Material onlineMaterial;
    private Material offlineMaterial;

    public ServerSelectorMenu(LoginQueue2 plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        loadConfig();
    }

    /**
     * 从配置文件读取菜单显示相关配置
     */
    private void loadConfig() {
        this.title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("udp-sync.server-selector.title", "&a选择要加入的服务器"));
        this.onlineMaterial = parseMaterial(plugin.getConfig().getString("udp-sync.server-selector.online-material", "LIME_STAINED_GLASS_PANE"), Material.LIME_STAINED_GLASS_PANE);
        this.offlineMaterial = parseMaterial(plugin.getConfig().getString("udp-sync.server-selector.offline-material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
    }

    /**
     * 判断服务器选择菜单是否已启用
     * 仅在 UDP 同步启用且非 WORLD 模式下生效
     */
    public static boolean isEnabled(LoginQueue2 plugin) {
        if (!plugin.getConfig().getBoolean("udp-sync.enabled", false)) {
            return false;
        }
        LoginWorldManager loginWorldManager = plugin.getLoginWorldManager();
        if (loginWorldManager != null && loginWorldManager.isWorldMode()) {
            return false;
        }
        return plugin.getConfig().getBoolean("udp-sync.server-selector.enabled", false);
    }

    /**
     * 打开服务器选择菜单
     */
    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        loadConfig();

        List<ServerEntry> entries = buildServerEntries();
        int size = calculateSize(entries.size());

        Inventory inventory = Bukkit.createInventory(new ServerSelectorHolder(), size, title);

        if (entries.isEmpty()) {
            ItemStack infoItem = createInfoItem();
            inventory.setItem(size / 2, infoItem);
        } else {
            for (int i = 0; i < entries.size(); i++) {
                ServerEntry entry = entries.get(i);
                inventory.setItem(i, createServerItem(entry));
            }
        }

        SchedulerUtil.runTask(plugin, () -> {
            if (player.isOnline()) {
                player.openInventory(inventory);
                // 菜单刚打开时缓存可能为空或已过期，触发状态刷新并异步更新界面
                refreshAndUpdateMenu(player);
            }
        });
    }

    /**
     * 触发服务器状态刷新，并在稍后更新已打开的选择菜单
     */
    private void refreshAndUpdateMenu(Player player) {
        BungeeMessenger messenger = plugin.getMessenger();
        if (messenger == null || !shouldRefresh(messenger)) {
            return;
        }

        messenger.refresh();

        // 在 0.5 秒、1.5 秒、3 秒后尝试更新菜单，以覆盖 UDP/MSLP/BC 各模式的响应时间
        SchedulerUtil.runTaskLater(plugin, () -> updateOpenMenu(player), 10L);
        SchedulerUtil.runTaskLater(plugin, () -> updateOpenMenu(player), 30L);
        SchedulerUtil.runTaskLater(plugin, () -> updateOpenMenu(player), 60L);
    }

    /**
     * 判断是否需要触发一次状态刷新
     * 当任一 UDP 配置的服务器处于离线/无缓存状态时触发
     */
    private boolean shouldRefresh(BungeeMessenger messenger) {
        List<Map<?, ?>> servers = plugin.getConfig().getMapList("udp-sync.servers");
        if (servers != null && !servers.isEmpty()) {
            for (Map<?, ?> map : servers) {
                Object nameObj = map.get("name");
                if (nameObj == null) {
                    continue;
                }
                if (!messenger.isServerOnline(String.valueOf(nameObj))) {
                    return true;
                }
            }
            return false;
        }
        // 兼容旧版单服务器配置
        String mainServer = plugin.getConfig().getString("queue.main-server", "main");
        return !messenger.isServerOnline(mainServer);
    }

    /**
     * 更新玩家当前打开的选择菜单内容
     */
    private void updateOpenMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (topInventory == null || !(topInventory.getHolder() instanceof ServerSelectorHolder)) {
            return;
        }

        List<ServerEntry> entries = buildServerEntries();
        if (entries.isEmpty()) {
            topInventory.setItem(topInventory.getSize() / 2, createInfoItem());
            return;
        }

        // 清空旧物品后重新设置，避免服务器数量变化时残留旧物品
        for (int i = 0; i < topInventory.getSize(); i++) {
            topInventory.setItem(i, null);
        }
        for (int i = 0; i < entries.size(); i++) {
            topInventory.setItem(i, createServerItem(entries.get(i)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ServerSelectorHolder)) {
            return;
        }

        // 禁止在菜单中移动、拖拽任何物品
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        String serverName = extractServerName(clicked);
        if (serverName == null) {
            return;
        }

        BungeeMessenger messenger = plugin.getMessenger();
        if (messenger == null) {
            player.sendMessage(languageManager.getMessage("server-selector-no-messenger"));
            player.closeInventory();
            return;
        }

        BungeeMessenger.ServerStatus status = messenger.getServerStatus(serverName);
        if (status == null || !messenger.isServerOnline(serverName)) {
            player.sendMessage(languageManager.getMessage("server-selector-offline", "server", serverName));
            player.closeInventory();
            return;
        }

        player.closeInventory();

        // 多服务器独立队列模式下，加入对应服务器的队列而不是直接连接
        if (plugin.getPlayerJoinListener() != null && plugin.getPlayerJoinListener().isPerServerQueueMode()) {
            plugin.getPlayerJoinListener().addPlayerToQueue(player, serverName);
            player.sendMessage(languageManager.getMessage("joined-queue"));
        } else {
            messenger.connectPlayerToServer(player, serverName);
            player.sendMessage(languageManager.getMessage("server-selector-connecting", "server", serverName));
        }
    }

    /**
     * 构建当前可显示的服务器列表（仅包含 UDP 同步配置中的服务器）
     */
    private List<ServerEntry> buildServerEntries() {
        List<ServerEntry> entries = new ArrayList<>();
        BungeeMessenger messenger = plugin.getMessenger();

        List<Map<?, ?>> servers = plugin.getConfig().getMapList("udp-sync.servers");
        if (servers != null && !servers.isEmpty()) {
            for (Map<?, ?> map : servers) {
                Object nameObj = map.get("name");
                String name = nameObj != null ? String.valueOf(nameObj) : null;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                BungeeMessenger.ServerStatus status = messenger != null ? messenger.getServerStatus(name) : null;
                entries.add(new ServerEntry(name, status));
            }
        } else {
            // 兼容旧版单服务器配置
            String mainServer = plugin.getConfig().getString("queue.main-server", "main");
            BungeeMessenger.ServerStatus status = messenger != null ? messenger.getServerStatus(mainServer) : null;
            entries.add(new ServerEntry(mainServer, status));
        }

        return entries;
    }

    private int calculateSize(int itemCount) {
        int rows = Math.max(1, Math.min(6, (itemCount + 8) / 9));
        return rows * 9;
    }

    private ItemStack createInfoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(languageManager.getMessage("server-selector-no-servers-name"));
            List<String> lore = new ArrayList<>();
            lore.add(languageManager.getMessage("server-selector-no-servers-lore"));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createServerItem(ServerEntry entry) {
        boolean online = entry.status != null && entry.status.isOnline();
        Material material = online ? onlineMaterial : offlineMaterial;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                languageManager.getMessage("server-selector-item-name",
                        "server", entry.name,
                        "status", languageManager.getMessage(online ? "online" : "offline"))));

        List<String> lore = new ArrayList<>();
        if (online && entry.status != null) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-status-online",
                            "online", String.valueOf(entry.status.getOnlinePlayers()),
                            "max", String.valueOf(entry.status.getMaxPlayers()))));
            double ratio = entry.status.getLoadRatio();
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-load",
                            "ratio", String.format("%.1f", ratio * 100))));
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-tps",
                            "tps", String.format("%.1f", entry.status.getTps()))));
        } else {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    languageManager.getMessage("server-selector-status-offline")));
        }
        lore.add(ChatColor.translateAlternateColorCodes('&',
                languageManager.getMessage("server-selector-click-hint")));
        // 隐藏的 ID 标记，用于点击时识别服务器
        lore.add(ChatColor.BLACK + LORE_ID_PREFIX + entry.name);

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String extractServerName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return null;
        }
        List<String> lore = meta.getLore();
        if (lore == null) {
            return null;
        }
        for (String line : lore) {
            String plain = ChatColor.stripColor(line);
            if (plain.startsWith(LORE_ID_PREFIX)) {
                return plain.substring(LORE_ID_PREFIX.length());
            }
        }
        return null;
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Material parsed = Material.getMaterial(name.toUpperCase());
        return parsed != null ? parsed : fallback;
    }

    /**
     * 自定义 InventoryHolder，用于识别服务器选择菜单
     */
    public static class ServerSelectorHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static class ServerEntry {
        final String name;
        final BungeeMessenger.ServerStatus status;

        ServerEntry(String name, BungeeMessenger.ServerStatus status) {
            this.name = name;
            this.status = status;
        }
    }
}
