package com.ethercats.siyuan.gui;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Installs GFMenu-style menu aliases while retaining ownership information so
 * a menu refresh never unregisters commands belonging to another plugin.
 */
public final class MenuCommandRegistry {
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_:-]+");
    private static final int MAX_COMMANDS_PER_MENU = 16;

    private final SiYuanPlugin plugin;
    private final DynamicMenuManager menuManager;
    private final MenuCommandStore<Command> registeredCommands = new MenuCommandStore<>();

    MenuCommandRegistry(SiYuanPlugin plugin, DynamicMenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    public void registerMenuCommands(String menuName, List<String> configuredCommands, String permission) {
        if (configuredCommands == null || configuredCommands.isEmpty()) return;

        int processed = 0;
        for (String configuredCommand : configuredCommands) {
            if (processed++ >= MAX_COMMANDS_PER_MENU) {
                plugin.getLogger().warning("[GFMenu] 菜单 " + menuName + " 的命令绑定超过 "
                    + MAX_COMMANDS_PER_MENU + " 个，已忽略其余项");
                return;
            }
            String commandName = normalizeCommand(configuredCommand);
            if (commandName == null) {
                plugin.getLogger().warning("[GFMenu] 忽略无效菜单命令: " + configuredCommand);
                continue;
            }
            if (registeredCommands.containsKey(commandName)) {
                plugin.getLogger().warning("[GFMenu] 命令 /" + commandName + " 已绑定到另一个菜单，已跳过 " + menuName);
                continue;
            }

            CommandMap commandMap = Bukkit.getCommandMap();
            if (commandMap.getCommand(commandName) != null) {
                plugin.getLogger().warning("[GFMenu] 命令 /" + commandName + " 已被占用，未覆盖现有命令");
                continue;
            }

            MenuOpenCommand command = new MenuOpenCommand(commandName, menuName, permission);
            if (!commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command)) {
                plugin.getLogger().warning("[GFMenu] 无法注册菜单命令 /" + commandName);
                continue;
            }
            registeredCommands.add(normalizeMenuName(menuName), commandName, command);
        }
    }

    public void unregisterMenuCommands(String menuName) {
        unregisterCommands(registeredCommands.removeOwner(normalizeMenuName(menuName)));
    }

    public void unregisterAllCommands() {
        unregisterCommands(registeredCommands.removeAll());
    }

    public int getRegisteredCommandCount() {
        return registeredCommands.size();
    }

    public List<String> getRegisteredCommandNames() {
        return new ArrayList<>(registeredCommands.keys());
    }

    /** Returns a normalized command label, or {@code null} for an unsafe label. */
    public static String normalizeCommand(String configuredCommand) {
        if (configuredCommand == null) return null;
        String commandName = configuredCommand.trim().toLowerCase(Locale.ROOT);
        if (commandName.startsWith("/")) commandName = commandName.substring(1);
        return COMMAND_NAME.matcher(commandName).matches() ? commandName : null;
    }

    private void unregisterCommands(Collection<Command> commands) {
        if (commands.isEmpty()) return;
        CommandMap commandMap = Bukkit.getCommandMap();
        Map<String, Command> knownCommands = commandMap.getKnownCommands();
        for (Command command : commands) {
            command.unregister(commandMap);
            knownCommands.entrySet().removeIf(entry -> entry.getValue() == command);
        }
    }

    private static String normalizeMenuName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private final class MenuOpenCommand extends Command {
        private final String menuName;

        private MenuOpenCommand(String commandName, String menuName, String permission) {
            super(commandName);
            this.menuName = menuName;
            setDescription("Open Siyuan menu " + menuName);
            setUsage("/" + commandName);
            if (permission != null && !permission.isBlank()) setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c只有玩家才能打开菜单");
                return true;
            }
            if (getPermission() != null && !player.hasPermission(getPermission())) {
                player.sendMessage("§c你没有权限打开这个菜单");
                return true;
            }
            menuManager.open(player, menuName);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return List.of();
        }
    }
}
