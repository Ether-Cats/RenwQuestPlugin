package com.ethercats.siyuan.gui;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.gui.DynamicMenuManager.EditableMenu;
import com.ethercats.siyuan.gui.DynamicMenuManager.EditableMenuItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory-based administrator editor for dynamic menus. Existing icons are
 * virtual and tagged for the lifetime of an edit session. Real template items
 * placed into the editor are returned when the session ends.
 */
public final class MenuEditorManager implements Listener {
    private static final String ADMIN_PERMISSION = "siyuan.admin";
    private static final EnumSet<InventoryAction> DROP_ACTIONS = EnumSet.of(
        InventoryAction.DROP_ALL_CURSOR,
        InventoryAction.DROP_ONE_CURSOR,
        InventoryAction.DROP_ALL_SLOT,
        InventoryAction.DROP_ONE_SLOT
    );

    private final SiYuanPlugin plugin;
    private final DynamicMenuManager menuManager;
    private final NamespacedKey editorItemKey;
    private final Map<UUID, EditorSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> activeMenus = new ConcurrentHashMap<>();

    public MenuEditorManager(SiYuanPlugin plugin, DynamicMenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.editorItemKey = new NamespacedKey(plugin, "menu_editor_item");
    }

    public void openEditor(Player player, String requestedName) {
        EditableMenu existing = menuManager.getEditableMenu(requestedName);
        if (existing == null) {
            openEditor(player, requestedName, 6, requestedName);
            return;
        }
        openEditor(player, existing, existing.rows(), existing.title());
    }

    public void openEditor(Player player, String requestedName, int rows, String title) {
        EditableMenu existing = menuManager.getEditableMenu(requestedName);
        String name = normalize(requestedName);
        EditableMenu source = existing == null
            ? new EditableMenu(name, title, rows, null, List.of(), List.of(), List.of())
            : existing;
        openEditor(player, source, rows, title);
    }

    private void openEditor(Player player, EditableMenu source, int requestedRows, String requestedTitle) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage("§c你没有菜单编辑权限");
            return;
        }
        if (!menuManager.isValidMenuName(source.name())) {
            player.sendMessage("§c菜单名只能包含字母、数字、下划线和连字符，最多 64 个字符");
            return;
        }
        if (requestedRows < 1 || requestedRows > 6) {
            player.sendMessage("§c菜单行数必须是 1 到 6");
            return;
        }

        UUID owner = player.getUniqueId();
        UUID activeEditor = activeMenus.get(source.name());
        if (activeEditor != null && !activeEditor.equals(owner)) {
            player.sendMessage("§c菜单 §f" + source.name() + "§c 正在由另一位管理员编辑");
            return;
        }

        EditorSession previous = sessions.get(owner);
        if (previous != null) finishSession(previous, player, true, false);

        String title = requestedTitle == null || requestedTitle.isBlank() ? source.name() : requestedTitle;
        if (title.length() > 64) title = title.substring(0, 64);
        UUID sessionId = UUID.randomUUID();
        EditorHolder holder = new EditorHolder(owner, sessionId);
        String editorTitle = color("&8菜单编辑: &f" + source.name());
        Inventory inventory = Bukkit.createInventory(holder, requestedRows * 9, editorTitle);
        holder.inventory = inventory;

        EditorSession session = new EditorSession(
            sessionId,
            owner,
            source.name(),
            title,
            requestedRows,
            source.permission(),
            source.openActions(),
            source.closeActions(),
            inventory
        );
        for (EditableMenuItem item : source.items()) {
            if (item.slot() < 0 || item.slot() >= inventory.getSize() || item.item() == null) continue;
            String token = sessionId + ":" + UUID.randomUUID();
            session.actions.put(token, new ActionBinding(item.leftActions(), item.rightActions(), item.allActions()));
            inventory.setItem(item.slot(), tagVirtualItem(item.item(), token));
        }

        sessions.put(owner, session);
        activeMenus.put(source.name(), owner);
        player.openInventory(inventory);
        player.sendMessage("§a正在编辑菜单 §f" + source.name()
            + "§a；放置、移动或移除物品后关闭界面即自动保存");
        player.sendMessage("§7也可使用 §f/gc menu save §7立即保存，或 §f/gc menu cancel §7放弃更改");
    }

    public void saveOpenEditor(Player player) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c你当前没有正在编辑的菜单");
            return;
        }
        finishSession(session, player, true, true);
    }

    public void cancelOpenEditor(Player player) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c你当前没有正在编辑的菜单");
            return;
        }
        finishSession(session, player, false, true);
    }

    public boolean hasOpenEditor(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void setItemAction(Player player, int slot, boolean rightClick, String requestedAction) {
        setItemAction(player, slot, rightClick ? "right" : "left", requestedAction);
    }

    public void setItemAction(Player player, int slot, String requestedType, String requestedAction) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c请先使用 /gc menu edit 打开一个菜单");
            return;
        }
        if (slot < 0 || slot >= session.inventory.getSize()) {
            player.sendMessage("§c槽位必须在 0 到 " + (session.inventory.getSize() - 1) + " 之间");
            return;
        }
        ItemStack current = session.inventory.getItem(slot);
        if (current == null || current.getType().isAir()) {
            player.sendMessage("§c该槽位没有物品");
            return;
        }
        String type = requestedType == null ? "" : requestedType.toLowerCase(Locale.ROOT);
        if (!type.equals("left") && !type.equals("right") && !type.equals("all")) {
            player.sendMessage("§c点击类型只能是 left、right 或 all");
            return;
        }
        String action = MenuActionCodec.normalize(requestedAction == null ? "" : requestedAction.trim());
        boolean clear = action.equalsIgnoreCase("clear") || action.equalsIgnoreCase("none");
        if (!clear && !isSupportedAction(action)) {
            player.sendMessage("§c动作格式无效，可用 command:/console:/tell:/chat:/menu:/sound: 或 close");
            return;
        }

        String token = getEditorToken(current);
        if (token == null) {
            token = session.id + ":" + UUID.randomUUID();
            session.templateTokens.add(token);
            session.inventory.setItem(slot, tagVirtualItem(current, token));
        }
        ActionBinding existing = session.actions.getOrDefault(token, new ActionBinding(List.of(), List.of(), List.of()));
        List<String> actions = clear ? List.of() : List.of(action);
        session.actions.put(token, switch (type) {
            case "right" -> new ActionBinding(existing.leftActions, actions, existing.allActions);
            case "all" -> new ActionBinding(existing.leftActions, existing.rightActions, actions);
            default -> new ActionBinding(actions, existing.rightActions, existing.allActions);
        });
        player.sendMessage(clear
            ? "§a已清除槽位 " + slot + " 的 " + type + " 动作"
            : "§a已设置槽位 " + slot + " 的 " + type + " 动作");
    }

    public void setTitle(Player player, String title) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c请先使用 /gc menu edit 打开一个菜单");
            return;
        }
        String value = title == null ? "" : title.trim();
        if (value.isBlank() || value.length() > 64) {
            player.sendMessage("§c标题长度必须在 1 到 64 个字符之间");
            return;
        }
        session.title = value;
        player.sendMessage("§a菜单标题已更新，保存后生效");
    }

    public void setPermission(Player player, String permission) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§c请先使用 /gc menu edit 打开一个菜单");
            return;
        }
        String value = permission == null ? "" : permission.trim();
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("clear")) value = "";
        if (value.length() > 128 || (!value.isBlank() && !value.matches("[A-Za-z0-9._*-]+"))) {
            player.sendMessage("§c权限节点格式无效");
            return;
        }
        session.permission = value.isBlank() ? null : value;
        player.sendMessage(value.isBlank() ? "§a已清除菜单打开权限" : "§a菜单打开权限已设置为 §f" + value);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        EditorSession session = sessions.get(player.getUniqueId());
        if (!holder.owner.equals(player.getUniqueId())
            || session == null
            || !session.id.equals(holder.sessionId)
            || !player.hasPermission(ADMIN_PERMISSION)) {
            event.setCancelled(true);
            if (session != null) finishSession(session, player, false, true);
            else player.closeInventory();
            return;
        }
        // Dropping is never needed for editing and could leak a virtual icon.
        if (DROP_ACTIONS.contains(event.getAction())) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean topSlot = event.getRawSlot() >= 0 && event.getRawSlot() < topSize;
        if (!topSlot && isSessionVirtualItem(event.getCursor(), session)) {
            event.setCancelled(true);
            return;
        }
        if (topSlot && isSessionVirtualItem(event.getCurrentItem(), session)
            && (event.isShiftClick() || event.getClick() == ClickType.NUMBER_KEY
            || event.getAction() == InventoryAction.HOTBAR_SWAP
            || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)
            || !holder.owner.equals(player.getUniqueId())
            || !player.hasPermission(ADMIN_PERMISSION)) {
            event.setCancelled(true);
            return;
        }
        EditorSession session = sessions.get(player.getUniqueId());
        if (session != null && isSessionVirtualItem(event.getOldCursor(), session)
            && event.getRawSlots().stream().anyMatch(slot -> slot >= event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        EditorSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.id.equals(holder.sessionId)) return;
        finishSession(session, player, true, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        EditorSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) finishSession(session, event.getPlayer(), true, false);
    }

    public void shutdown() {
        for (EditorSession session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.owner);
            if (player != null) finishSession(session, player, true, false);
            else {
                sessions.remove(session.owner, session);
                activeMenus.remove(session.name, session.owner);
            }
        }
    }

    private void finishSession(EditorSession session, Player player, boolean save, boolean closeInventory) {
        if (session.finished || !sessions.remove(session.owner, session)) return;
        session.finished = true;
        activeMenus.remove(session.name, session.owner);

        List<ItemStack> templatesToReturn = new ArrayList<>();
        List<EditableMenuItem> items = snapshotItems(session, templatesToReturn);
        boolean saved = false;
        if (save) {
            EditableMenu menu = new EditableMenu(
                session.name,
                session.title,
                session.rows,
                session.permission,
                session.openActions,
                session.closeActions,
                items
            );
            try {
                menuManager.saveEditableMenu(menu);
                saved = true;
            } catch (Exception ex) {
                plugin.getLogger().severe("菜单保存失败 " + session.name + ": " + ex.getMessage());
                player.sendMessage("§c菜单保存失败，原文件未被覆盖；请检查控制台日志");
            }
        }
        if (saved && plugin.getRemoteMenuSyncService().isEnabled()) {
            try {
                plugin.getRemoteMenuSyncService().pushLocalMenu(
                    session.name, menuManager.getMenuSourceYaml(session.name), player.getName());
            } catch (IOException ex) {
                plugin.getLogger().warning("无法读取刚保存的菜单用于 Web 同步: " + ex.getMessage());
            }
        }

        session.inventory.clear();
        scrubVirtualItems(player, session);
        returnTemplateItems(player, templatesToReturn);
        if (closeInventory && isViewingSession(player, session.id)) player.closeInventory();

        if (!save) player.sendMessage("§e已放弃菜单 §f" + session.name + "§e 的更改");
        else if (saved) player.sendMessage("§a菜单 §f" + session.name + "§a 已保存并重新加载");
    }

    private List<EditableMenuItem> snapshotItems(EditorSession session, List<ItemStack> templatesToReturn) {
        List<EditableMenuItem> result = new ArrayList<>();
        for (int slot = 0; slot < session.inventory.getSize(); slot++) {
            ItemStack current = session.inventory.getItem(slot);
            if (current == null || current.getType().isAir()) continue;
            String token = getEditorToken(current);
            ActionBinding binding = token == null ? null : session.actions.get(token);
            ItemStack clean = current.clone();
            removeEditorToken(clean);
            result.add(new EditableMenuItem(
                slot,
                clean,
                binding == null ? List.of() : binding.leftActions,
                binding == null ? List.of() : binding.rightActions,
                binding == null ? List.of() : binding.allActions
            ));
            if (token == null || session.templateTokens.contains(token)) templatesToReturn.add(clean.clone());
        }
        return result;
    }

    private void returnTemplateItems(Player player, List<ItemStack> templates) {
        for (ItemStack item : templates) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void scrubVirtualItems(Player player, EditorSession session) {
        String prefix = session.id + ":";
        ItemStack[] contents = player.getInventory().getContents();
        for (int index = 0; index < contents.length; index++) {
            String token = getEditorToken(contents[index]);
            if (token != null && token.startsWith(prefix)) {
                if (session.templateTokens.contains(token)) {
                    ItemStack clean = contents[index].clone();
                    removeEditorToken(clean);
                    player.getInventory().setItem(index, clean);
                } else {
                    player.getInventory().setItem(index, null);
                }
            }
        }
        String cursorToken = getEditorToken(player.getItemOnCursor());
        if (cursorToken != null && cursorToken.startsWith(prefix)) {
            if (session.templateTokens.contains(cursorToken)) {
                ItemStack clean = player.getItemOnCursor().clone();
                removeEditorToken(clean);
                player.setItemOnCursor(clean);
            } else {
                player.setItemOnCursor(new ItemStack(Material.AIR));
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        String offHandToken = getEditorToken(offHand);
        if (offHandToken != null && offHandToken.startsWith(prefix)) {
            if (session.templateTokens.contains(offHandToken)) {
                ItemStack clean = offHand.clone();
                removeEditorToken(clean);
                player.getInventory().setItemInOffHand(clean);
            } else {
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            }
        }
    }

    private boolean isSupportedAction(String action) {
        return MenuActionCodec.isSupported(action);
    }

    private ItemStack tagVirtualItem(ItemStack original, String token) {
        ItemStack tagged = original.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(editorItemKey, PersistentDataType.STRING, token);
            tagged.setItemMeta(meta);
        }
        return tagged;
    }

    private String getEditorToken(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(editorItemKey, PersistentDataType.STRING);
    }

    private boolean isSessionVirtualItem(ItemStack item, EditorSession session) {
        String token = getEditorToken(item);
        return token != null && token.startsWith(session.id + ":");
    }

    private void removeEditorToken(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(editorItemKey);
        item.setItemMeta(meta);
    }

    private boolean isViewingSession(Player player, UUID sessionId) {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof EditorHolder editor && editor.sessionId.equals(sessionId);
    }

    private String normalize(String name) {
        if (name == null) return "";
        return name.replaceFirst("(?i)\\.(yml|yaml)$", "").toLowerCase(Locale.ROOT);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private static final class EditorSession {
        private final UUID id;
        private final UUID owner;
        private final String name;
        private String title;
        private final int rows;
        private String permission;
        private final List<String> openActions;
        private final List<String> closeActions;
        private final Inventory inventory;
        private final Map<String, ActionBinding> actions = new HashMap<>();
        private final Set<String> templateTokens = new HashSet<>();
        private boolean finished;

        private EditorSession(
            UUID id,
            UUID owner,
            String name,
            String title,
            int rows,
            String permission,
            List<String> openActions,
            List<String> closeActions,
            Inventory inventory
        ) {
            this.id = id;
            this.owner = owner;
            this.name = name;
            this.title = title;
            this.rows = rows;
            this.permission = permission;
            this.openActions = List.copyOf(openActions);
            this.closeActions = List.copyOf(closeActions);
            this.inventory = inventory;
        }
    }

    private record ActionBinding(List<String> leftActions, List<String> rightActions, List<String> allActions) {
        private ActionBinding {
            leftActions = List.copyOf(leftActions);
            rightActions = List.copyOf(rightActions);
            allActions = List.copyOf(allActions);
        }
    }

    private static final class EditorHolder implements InventoryHolder {
        private final UUID owner;
        private final UUID sessionId;
        private Inventory inventory;

        private EditorHolder(UUID owner, UUID sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
