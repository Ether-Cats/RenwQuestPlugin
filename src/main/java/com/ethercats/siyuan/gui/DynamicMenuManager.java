package com.ethercats.siyuan.gui;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Small, safe compatibility layer for the common GFMenu/TrMenu/DeluxeMenus
 * layout and action syntax. Menus are data-driven and never trust item names
 * from the player inventory; only this holder's action map is executed.
 */
public final class DynamicMenuManager implements Listener {
    private final SiYuanPlugin plugin;
    private final Map<String, MenuDefinition> menus = new ConcurrentHashMap<>();
    private final MenuInputManager inputManager;

    public DynamicMenuManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.inputManager = new MenuInputManager(plugin, this);
        reload();
    }

    public void reload() {
        menus.clear();
        File folder = new File(plugin.getDataFolder(), "menus");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("[GFMenu] 无法创建菜单目录: " + folder);
            return;
        }
        Path menuRoot = folder.toPath().toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(menuRoot)) {
            files = paths
                .filter(Files::isRegularFile)
                .filter(path -> isMenuFile(menuRoot, path))
                .sorted(Comparator
                    .comparingInt((Path path) -> isRemoteMenu(menuRoot, path) ? 1 : 0)
                    .thenComparing(Path::toString))
                .toList();
        } catch (IOException ex) {
            plugin.getLogger().warning("[GFMenu] 无法读取菜单目录: " + ex.getMessage());
            return;
        }
        for (Path path : files) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(path.toFile());
                String sourceFileName = menuRoot.relativize(path).toString().replace(File.separatorChar, '/');
                MenuDefinition menu = config.contains("layout") || config.contains("Layout") || config.contains("Icons")
                    ? parseTrMenu(sourceFileName, config) : parseDeluxe(sourceFileName, config);
                if (menu != null && menus.putIfAbsent(menu.name, menu) != null) {
                    plugin.getLogger().warning("[GFMenu] 忽略重复菜单标识 " + menu.name + ": " + sourceFileName);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("[GFMenu] 菜单加载失败 " + path.getFileName() + ": " + ex.getMessage());
            }
        }
        plugin.getLogger().info("[GFMenu] 已加载 " + menus.size() + " 个菜单");
    }

    public List<String> getMenuNames() {
        return menus.keySet().stream().sorted().toList();
    }

    public MenuInputManager getInputManager() {
        return inputManager;
    }

    public void shutdown() {
        inputManager.shutdown();
    }

    /**
     * Returns a detached menu snapshot suitable for an editor. ItemStacks and
     * action lists are copied, so callers cannot mutate a live menu.
     */
    public EditableMenu getEditableMenu(String name) {
        MenuDefinition menu = menus.get(normalize(name));
        if (menu == null) return null;
        List<EditableMenuItem> items = menu.items.values().stream()
            .filter(item -> item.slot >= 0 && item.slot < menu.size)
            .sorted(Comparator.comparingInt(item -> item.slot))
            .map(item -> new EditableMenuItem(
                item.slot,
                item.toEditorItemStack(),
                item.leftActions,
                item.rightActions,
                item.allActions
            ))
            .toList();
        return new EditableMenu(
            menu.name,
            menu.title,
            menu.size / 9,
            menu.permission,
            menu.openActions,
            menu.closeActions,
            items
        );
    }

    public boolean isValidMenuName(String name) {
        String normalized = normalize(name);
        return normalized.matches("[a-z0-9_-]{1,64}");
    }

    public String getMenuSourceYaml(String name) throws IOException {
        MenuDefinition menu = menus.get(normalize(name));
        if (menu == null) throw new IOException("菜单不存在: " + name);
        Path folder = plugin.getDataFolder().toPath().resolve("menus").toAbsolutePath().normalize();
        Path source = folder.resolve(menu.sourceFileName).normalize();
        if (!source.startsWith(folder) || !Files.isRegularFile(source)) throw new IOException("菜单源文件无效");
        if (Files.size(source) > 1024L * 1024L) throw new IOException("菜单源文件超过 1MB");
        return Files.readString(source);
    }

    /**
     * Writes a DeluxeMenus-compatible document through a temporary file and
     * atomically replaces the old file where the filesystem supports it.
     */
    public synchronized void saveEditableMenu(EditableMenu draft) throws IOException {
        if (draft == null || !isValidMenuName(draft.name())) {
            throw new IllegalArgumentException("菜单名只能包含字母、数字、下划线和连字符");
        }
        String name = normalize(draft.name());
        int rows = Math.max(1, Math.min(6, draft.rows()));
        YamlConfiguration config = new YamlConfiguration();
        config.set("siyuan_menu_key", name);
        config.set("menu_title", toAmpersandColors(draft.title()));
        config.set("size", rows * 9);
        boolean requiresPermission = draft.permission() != null && !draft.permission().isBlank();
        config.set("open_requires_permission", requiresPermission);
        if (requiresPermission) config.set("open_permission", draft.permission().trim());
        if (!draft.openActions().isEmpty()) {
            config.set("open_commands", toDeluxeActions(draft.openActions()));
        }
        if (!draft.closeActions().isEmpty()) {
            config.set("close_commands", toDeluxeActions(draft.closeActions()));
        }

        draft.items().stream()
            .filter(item -> item.slot() >= 0 && item.slot() < rows * 9)
            .sorted(Comparator.comparingInt(EditableMenuItem::slot))
            .forEach(item -> writeDeluxeItem(config, item));

        File folder = new File(plugin.getDataFolder(), "menus");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("无法创建菜单目录: " + folder);
        }
        MenuDefinition existing = menus.get(name);
        String fileName = existing == null ? name + ".yml" : existing.sourceFileName;
        Path menuRoot = folder.toPath().toAbsolutePath().normalize();
        Path target = menuRoot.resolve(fileName).normalize();
        if (!target.startsWith(menuRoot)) throw new IOException("菜单目标路径无效");
        Files.createDirectories(target.getParent());
        backupExistingMenu(menuRoot, target);
        Path temporary = Files.createTempFile(target.getParent(), name + "-", ".tmp");
        try {
            config.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        reload();
    }

    public boolean open(Player player, String name) {
        MenuDefinition menu = menus.get(normalize(name));
        if (menu == null) {
            player.sendMessage("§c菜单不存在: " + name);
            return false;
        }
        if (menu.permission != null && !menu.permission.isBlank() && !player.hasPermission(menu.permission)) {
            player.sendMessage("§c你没有权限打开这个菜单");
            return false;
        }
        DynamicHolder holder = new DynamicHolder(menu);
        Inventory inventory = Bukkit.createInventory(holder, menu.size, color(MenuActionCodec.replacePlaceholders(menu.title, player)));
        holder.inventory = inventory;
        for (MenuItem item : menu.items.values()) {
            if (item.slot < 0 || item.slot >= menu.size) continue;
            inventory.setItem(item.slot, item.toItemStack(player));
        }
        player.openInventory(inventory);
        runActions(player, menu.openActions);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DynamicHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        MenuDefinition menu = holder.menu;
        MenuItem item = menu.items.get(slot);
        if (item == null) return;
        List<String> actions = event.isRightClick() ? item.rightActions : item.leftActions;
        runActions(player, actions.isEmpty() ? item.allActions : actions);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DynamicHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof DynamicHolder holder)) return;
        if (event.getPlayer() instanceof Player player) runActions(player, holder.menu.closeActions);
    }

    private MenuDefinition parseDeluxe(String fileName, YamlConfiguration config) {
        String name = normalize(config.getString("siyuan_menu_key", stripExtension(fileName)));
        MenuDefinition menu = new MenuDefinition(name, fileName);
        menu.title = config.getString("menu_title", config.getString("title", name));
        menu.size = validSize(config.getInt("menu_size", config.getInt("size", 54)));
        menu.permission = config.getString("open_permission");
        if (!config.getBoolean("open_requires_permission", menu.permission != null)) menu.permission = null;
        menu.openActions = MenuActionCodec.fromDeluxe(config.getStringList("open_commands"));
        menu.closeActions = MenuActionCodec.fromDeluxe(config.getStringList("close_commands"));
        ConfigurationSection itemContainer = config.getConfigurationSection("items");
        if (itemContainer != null) parseDeluxeItems(menu, itemContainer);
        parseDeluxeItems(menu, config);
        return menu;
    }

    private void parseDeluxeItems(MenuDefinition menu, ConfigurationSection source) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection section = source.getConfigurationSection(key);
            if (section == null || !section.contains("slot")) continue;
            MenuItem item = new MenuItem();
            item.slot = section.getInt("slot", -1);
            item.material = section.getString("material", "STONE");
            item.amount = section.getInt("amount", 1);
            item.name = section.getString("display_name", section.getString("name", key));
            item.lore = section.getStringList("lore");
            item.glowing = section.getBoolean("glow", false);
            item.skullOwner = section.getString("skull_owner");
            item.leftActions = MenuActionCodec.fromDeluxe(section.getStringList("left_click_commands"));
            item.rightActions = MenuActionCodec.fromDeluxe(section.getStringList("right_click_commands"));
            item.allActions = MenuActionCodec.fromDeluxe(section.getStringList("click_commands"));
            menu.items.put(item.slot, item);
        }
    }

    private MenuDefinition parseTrMenu(String fileName, YamlConfiguration config) {
        String name = normalize(config.getString("siyuan_menu_key", stripExtension(fileName)));
        MenuDefinition menu = new MenuDefinition(name, fileName);
        menu.title = config.getString("Title", config.getString("title", name));
        List<String> rows = config.contains("layout")
            ? config.getStringList("layout")
            : config.getStringList("Layout");
        menu.size = validSize(Math.max(1, rows.isEmpty() ? config.getInt("size", 54) : rows.size() * 9));
        menu.permission = config.getString("Settings.permission");
        menu.openActions = MenuActionCodec.fromDeluxe(config.getStringList("Events.Open"));
        menu.closeActions = MenuActionCodec.fromDeluxe(config.getStringList("Events.Close"));
        Map<Character, Integer> slots = layoutSlots(rows, config.getBoolean("Settings.center", true));
        ConfigurationSection icons = config.getConfigurationSection("Icons");
        if (icons != null) {
            for (String key : icons.getKeys(false)) {
                if (key.length() != 1 || !slots.containsKey(key.charAt(0))) continue;
                ConfigurationSection section = icons.getConfigurationSection(key);
                if (section == null) continue;
                MenuItem item = new MenuItem();
                item.slot = slots.get(key.charAt(0));
                ConfigurationSection display = section.getConfigurationSection("display");
                ConfigurationSection source = display == null ? section : display;
                item.material = source.getString("material", "STONE");
                item.amount = source.getInt("amount", 1);
                item.name = source.getString("name", source.getString("display_name", key));
                item.lore = source.getStringList("lore");
                item.glowing = source.getBoolean("glow", section.getBoolean("glow", false));
                item.skullOwner = source.getString("skull_owner", section.getString("skull_owner"));
                Object actions = section.get("actions");
                if (actions instanceof ConfigurationSection actionSection) {
                    item.leftActions = MenuActionCodec.fromDeluxe(actionSection.getStringList("left"));
                    item.rightActions = MenuActionCodec.fromDeluxe(actionSection.getStringList("right"));
                    item.allActions = MenuActionCodec.fromDeluxe(actionSection.getStringList("all"));
                } else {
                    item.allActions = MenuActionCodec.fromDeluxe(section.getStringList("actions"));
                }
                menu.items.put(item.slot, item);
            }
        }
        return menu;
    }

    private List<String> toDeluxeActions(List<String> actions) {
        return MenuActionCodec.toDeluxe(actions);
    }

    private void writeDeluxeItem(YamlConfiguration config, EditableMenuItem editable) {
        ItemStack stack = editable.item();
        if (stack == null || stack.getType().isAir()) return;
        String path = "items.item_" + editable.slot();
        config.set(path + ".slot", editable.slot());
        config.set(path + ".material", stack.getType().name());
        if (stack.getAmount() != 1) config.set(path + ".amount", stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                config.set(path + ".display_name", toAmpersandColors(meta.getDisplayName()));
            }
            List<String> lore = meta.getLore();
            if (lore != null && !lore.isEmpty()) {
                config.set(path + ".lore", lore.stream().map(this::toAmpersandColors).toList());
            }
            if (!stack.getEnchantments().isEmpty()) config.set(path + ".glow", true);
            if (meta instanceof SkullMeta skullMeta && skullMeta.getOwner() != null && !skullMeta.getOwner().isBlank()) {
                config.set(path + ".skull_owner", skullMeta.getOwner());
            }
        }
        if (!editable.leftActions().isEmpty()) {
            config.set(path + ".left_click_commands", toDeluxeActions(editable.leftActions()));
        }
        if (!editable.rightActions().isEmpty()) {
            config.set(path + ".right_click_commands", toDeluxeActions(editable.rightActions()));
        }
        if (!editable.allActions().isEmpty()) {
            config.set(path + ".click_commands", toDeluxeActions(editable.allActions()));
        }
    }

    private Map<Character, Integer> layoutSlots(List<String> rows, boolean centered) {
        Map<Character, Integer> slots = new HashMap<>();
        for (int row = 0; row < rows.size() && row < 6; row++) {
            String line = cleanLayoutRow(rows.get(row));
            int visibleLength = Math.min(line.length(), 9);
            boolean hasExplicitSpacing = line.length() >= 9 || line.startsWith(" ") || line.endsWith(" ");
            int start = centered && !hasExplicitSpacing && visibleLength < 9 ? (9 - visibleLength) / 2 : 0;
            for (int col = 0; col < visibleLength && start + col < 9; col++) {
                char ch = line.charAt(col);
                if (ch != ' ') slots.put(ch, row * 9 + start + col);
            }
        }
        return slots;
    }

    void runActions(Player player, List<String> actions) {
        if (actions == null) return;
        for (String raw : actions) {
            executeAction(player, raw);
        }
    }

    void executeAction(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        String action = MenuActionCodec.normalize(MenuActionCodec.replacePlaceholders(raw, player));
        int split = action.indexOf(':');
        String type = split < 0 ? action.toLowerCase(Locale.ROOT) : action.substring(0, split).toLowerCase(Locale.ROOT);
        String value = split < 0 ? "" : action.substring(split + 1).trim();
        if (value.startsWith(" ")) value = value.trim();
        switch (type) {
            case "tell", "message", "msg" -> player.sendMessage(color(value));
            case "command", "cmd" -> player.performCommand(stripSlash(value));
            case "chat" -> player.chat(value);
            case "op", "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value));
            case "close" -> player.closeInventory();
            case "menu", "open" -> open(player, value);
            case "sound" -> playSound(player, value);
            case "catcher" -> {
                player.closeInventory();
                inputManager.startCatcher(player, action);
            }
            case "book" -> {
                player.closeInventory();
                inputManager.startBook(player, action);
            }
            default -> plugin.getLogger().fine("[GFMenu] 未知动作: " + action);
        }
    }

    private void playSound(Player player, String value) {
        String[] parts = value.split("[-, ]+");
        try {
            Sound sound = Sound.valueOf(parts[0].toUpperCase(Locale.ROOT).replace(':', '_'));
            float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ex) {
            plugin.getLogger().fine("[GFMenu] 声音动作无效: " + value);
        }
    }

    private String stripSlash(String command) { return command.startsWith("/") ? command.substring(1) : command; }
    private String normalize(String name) { return name == null ? "" : stripExtension(name).toLowerCase(Locale.ROOT); }
    private String stripExtension(String value) { return value.replaceFirst("(?i)\\.(yml|yaml)$", "").toLowerCase(Locale.ROOT); }
    private int validSize(int size) {
        int bounded = Math.max(9, Math.min(54, size));
        return ((bounded + 8) / 9) * 9;
    }
    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
    private String toAmpersandColors(String value) { return value == null ? "" : value.replace(ChatColor.COLOR_CHAR, '&'); }

    public record EditableMenu(
        String name,
        String title,
        int rows,
        String permission,
        List<String> openActions,
        List<String> closeActions,
        List<EditableMenuItem> items
    ) {
        public EditableMenu {
            openActions = openActions == null ? List.of() : List.copyOf(openActions);
            closeActions = closeActions == null ? List.of() : List.copyOf(closeActions);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record EditableMenuItem(
        int slot,
        ItemStack item,
        List<String> leftActions,
        List<String> rightActions,
        List<String> allActions
    ) {
        public EditableMenuItem {
            item = item == null ? null : item.clone();
            leftActions = leftActions == null ? List.of() : List.copyOf(leftActions);
            rightActions = rightActions == null ? List.of() : List.copyOf(rightActions);
            allActions = allActions == null ? List.of() : List.copyOf(allActions);
        }

        @Override
        public ItemStack item() {
            return item == null ? null : item.clone();
        }
    }

    private static final class MenuDefinition {
        private final String name;
        private final String sourceFileName;
        private String title = "菜单";
        private int size = 54;
        private String permission;
        private List<String> openActions = List.of();
        private List<String> closeActions = List.of();
        private final Map<Integer, MenuItem> items = new LinkedHashMap<>();
        private MenuDefinition(String name, String sourceFileName) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.sourceFileName = sourceFileName;
        }
    }

    private static final class MenuItem {
        private int slot = -1;
        private String material = "STONE";
        private int amount = 1;
        private String name = "物品";
        private List<String> lore = List.of();
        private List<String> leftActions = List.of();
        private List<String> rightActions = List.of();
        private List<String> allActions = List.of();
        private boolean glowing;
        private String skullOwner;

        private ItemStack toItemStack(Player player) {
            Material type;
            type = Material.matchMaterial(material == null ? "" : material);
            if (type == null || type.isAir()) type = Material.STONE;
            ItemStack stack = new ItemStack(type, Math.max(1, Math.min(type.getMaxStackSize(), amount)));
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                String displayName = player == null ? name : MenuActionCodec.replacePlaceholders(name, player);
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName == null ? "" : displayName));
                meta.setLore(lore.stream().map(line -> {
                    String value = player == null ? line : MenuActionCodec.replacePlaceholders(line, player);
                    return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
                }).toList());
                if (glowing) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                if (meta instanceof SkullMeta skullMeta && skullOwner != null && !skullOwner.isBlank()) {
                    skullMeta.setOwner(skullOwner);
                }
                stack.setItemMeta(meta);
            }
            return stack;
        }

        private ItemStack toEditorItemStack() {
            return toItemStack(null);
        }
    }

    private static final class DynamicHolder implements InventoryHolder {
        private final MenuDefinition menu;
        private Inventory inventory;
        private DynamicHolder(MenuDefinition menu) { this.menu = menu; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private boolean isMenuFile(Path root, Path path) {
        Path relative = root.relativize(path);
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equals(".backups")) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private boolean isRemoteMenu(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && relative.getName(0).toString().equals(".remote");
    }

    private void backupExistingMenu(Path root, Path target) throws IOException {
        if (!Files.isRegularFile(target)) return;
        Path backupDirectory = root.resolve(".backups");
        Files.createDirectories(backupDirectory);
        String safeName = target.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Files.copy(target, backupDirectory.resolve(System.currentTimeMillis() + "-" + safeName));
    }

    private String cleanLayoutRow(String row) {
        if (row == null) return "";
        String trimmed = row.trim();
        if (trimmed.length() >= 2
            && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
            || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return row;
    }
}
