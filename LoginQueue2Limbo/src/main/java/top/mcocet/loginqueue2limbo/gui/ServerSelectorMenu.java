package top.mcocet.loginqueue2limbo.gui;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.EventPriority;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.inventory.InventoryClickEvent;
import com.loohp.limbo.inventory.CustomInventory;
import com.loohp.limbo.inventory.Inventory;
import com.loohp.limbo.inventory.InventoryHolder;
import com.loohp.limbo.inventory.ItemStack;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.scheduler.LimboTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;
import top.mcocet.loginqueue2limbo.LoginQueue2Limbo;
import top.mcocet.loginqueue2limbo.bungee.BungeeMessenger;
import top.mcocet.loginqueue2limbo.util.LanguageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UDP 多主服务器选择菜单（Limbo 版）
 * 当 udp-sync.server-selector.enabled 为 true 时，
 * 玩家点击“加入游戏”按钮或使用 /join 命令将弹出此菜单。
 */
public class ServerSelectorMenu implements Listener {

    private static final Key CUSTOM_DATA_KEY = Key.key("minecraft:custom_data");
    private static final String SERVER_ID_TAG = "lq2_server";

    private final LoginQueue2Limbo plugin;
    private final LanguageManager languageManager;
    private Component title;
    private Key onlineMaterial;
    private Key offlineMaterial;

    public ServerSelectorMenu(LoginQueue2Limbo plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        loadConfig();
    }

    private void loadConfig() {
        String titleStr = plugin.getConfigValueString("udp-sync.server-selector.title", "&a选择要加入的服务器");
        this.title = color(titleStr);
        this.onlineMaterial = parseMaterial(plugin.getConfigValueString("udp-sync.server-selector.online-material", "minecraft:lime_stained_glass_pane"), Key.key("minecraft:lime_stained_glass_pane"));
        this.offlineMaterial = parseMaterial(plugin.getConfigValueString("udp-sync.server-selector.offline-material", "minecraft:red_stained_glass_pane"), Key.key("minecraft:red_stained_glass_pane"));
    }

    /**
     * 判断服务器选择菜单是否已启用（Limbo 下只需 UDP 同步开启且菜单开启）
     */
    public static boolean isEnabled(LoginQueue2Limbo plugin) {
        if (!plugin.getConfigValueBoolean("udp-sync.enabled", false)) {
            return false;
        }
        return plugin.getConfigValueBoolean("udp-sync.server-selector.enabled", false);
    }

    /**
     * 打开服务器选择菜单
     */
    public void open(Player player) {
        if (player == null || !player.isValid()) {
            return;
        }

        loadConfig();

        List<ServerEntry> entries = buildServerEntries();
        int size = calculateSize(entries.size());

        Inventory inventory = CustomInventory.create(title, size, new ServerSelectorHolder());

        if (entries.isEmpty()) {
            inventory.setItem(size / 2, createInfoItem());
        } else {
            for (int i = 0; i < entries.size(); i++) {
                inventory.setItem(i, createServerItem(entries.get(i)));
            }
        }

        Limbo.getInstance().getScheduler().runTask(plugin, new LimboTask() {
            @Override
            public void run() {
                if (player.isValid()) {
                    player.openInventory(inventory);
                    refreshAndUpdateMenu(player);
                }
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

        // 在 0.5 秒、1.5 秒、3 秒后尝试更新菜单
        Limbo.getInstance().getScheduler().runTaskLater(plugin, new LimboTask() {
            @Override
            public void run() {
                updateOpenMenu(player);
            }
        }, 10L);
        Limbo.getInstance().getScheduler().runTaskLater(plugin, new LimboTask() {
            @Override
            public void run() {
                updateOpenMenu(player);
            }
        }, 30L);
        Limbo.getInstance().getScheduler().runTaskLater(plugin, new LimboTask() {
            @Override
            public void run() {
                updateOpenMenu(player);
            }
        }, 60L);
    }

    /**
     * 判断是否需要触发一次状态刷新
     */
    private boolean shouldRefresh(BungeeMessenger messenger) {
        List<Map<?, ?>> servers = plugin.getConfigValueMapList("udp-sync.servers");
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
        String mainServer = plugin.getConfigValueString("queue.main-server", "main");
        return !messenger.isServerOnline(mainServer);
    }

    /**
     * 更新玩家当前打开的选择菜单内容
     */
    private void updateOpenMenu(Player player) {
        if (player == null || !player.isValid()) {
            return;
        }
        Inventory topInventory = player.getInventoryView().getTopInventory();
        if (topInventory == null || !(topInventory.getHolder() instanceof ServerSelectorHolder)) {
            return;
        }

        List<ServerEntry> entries = buildServerEntries();
        if (entries.isEmpty()) {
            topInventory.setItem(topInventory.getSize() / 2, createInfoItem());
            return;
        }

        for (int i = 0; i < topInventory.getSize(); i++) {
            topInventory.setItem(i, null);
        }
        for (int i = 0; i < entries.size(); i++) {
            topInventory.setItem(i, createServerItem(entries.get(i)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView() == null || !(event.getView().getTopInventory().getHolder() instanceof ServerSelectorHolder)) {
            return;
        }

        // 禁止在菜单中移动、拖拽任何物品
        event.setCancelled(true);

        Player player = event.getPlayer();
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
     * 构建当前可显示的服务器列表
     */
    private List<ServerEntry> buildServerEntries() {
        List<ServerEntry> entries = new ArrayList<>();
        BungeeMessenger messenger = plugin.getMessenger();

        List<Map<?, ?>> servers = plugin.getConfigValueMapList("udp-sync.servers");
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
            String mainServer = plugin.getConfigValueString("queue.main-server", "main");
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
        ItemStack item = new ItemStack(Key.key("minecraft:book"), 1);
        item = item.displayName(deserialize(languageManager.getMessage("server-selector-no-servers-name")));
        return item;
    }

    private ItemStack createServerItem(ServerEntry entry) {
        boolean online = entry.status != null && entry.status.isOnline();
        Key material = online ? onlineMaterial : offlineMaterial;
        ItemStack item = new ItemStack(material, 1);

        Component name = deserialize(languageManager.getMessage("server-selector-item-name",
                "server", entry.name,
                "status", languageManager.getMessage(online ? "online" : "offline")));

        // Limbo 不支持传统 Lore API，把额外信息附加到显示名称中
        if (online && entry.status != null) {
            String loadStr = String.format("%.1f", entry.status.getLoadRatio() * 100);
            String tpsStr = String.format("%.1f", entry.status.getTps());
            name = name.append(color(" &7| &e" + entry.status.getOnlinePlayers() + "/" + entry.status.getMaxPlayers()
                    + " &7| &e" + loadStr + "%" + " &7| TPS " + tpsStr));
        } else {
            name = name.append(color(" &7| ").append(deserialize(languageManager.getMessage("server-selector-status-offline"))));
        }

        item = item.displayName(name);
        item = setServerId(item, entry.name);
        return item;
    }

    private String extractServerName(ItemStack item) {
        if (item == null || item.type().equals(ItemStack.AIR.type())) {
            return null;
        }
        Map<Key, Tag<?>> components = item.components();
        if (components == null) {
            return null;
        }
        Tag<?> tag = components.get(CUSTOM_DATA_KEY);
        if (!(tag instanceof CompoundTag)) {
            return null;
        }
        CompoundTag compound = (CompoundTag) tag;
        if (!compound.containsKey(SERVER_ID_TAG)) {
            return null;
        }
        return compound.getString(SERVER_ID_TAG);
    }

    private ItemStack setServerId(ItemStack item, String serverName) {
        CompoundTag customData = new CompoundTag();
        customData.putString(SERVER_ID_TAG, serverName);

        Map<Key, Tag<?>> components = new HashMap<>();
        Map<Key, Tag<?>> original = item.components();
        if (original != null) {
            components.putAll(original);
        }
        components.put(CUSTOM_DATA_KEY, customData);
        return item.components(components);
    }

    private Key parseMaterial(String name, Key fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        String lower = name.toLowerCase();
        if (!lower.contains(":")) {
            lower = "minecraft:" + lower;
        }
        try {
            return Key.key(lower);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Component deserialize(String text) {
        // LanguageManager 已经将颜色代码转换为 § 形式
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    private Component color(String text) {
        // 处理直接使用 & 颜色代码的字符串
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    /**
     * 自定义 InventoryHolder，用于识别服务器选择菜单
     */
    public static class ServerSelectorHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }

        @Override
        public InventoryHolder getHolder() {
            return this;
        }

        @Override
        public com.loohp.limbo.location.Location getLocation() {
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
