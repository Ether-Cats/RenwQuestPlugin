package com.ethercats.siyuan.gui;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes the small action language shared by siyuan menus, DeluxeMenus
 * imports, and the newer GFMenu-compatible syntax.
 */
public final class MenuActionCodec {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "command", "cmd", "chat", "console", "op", "tell", "message", "msg", "menu", "open", "sound"
    );

    private MenuActionCodec() {
    }

    public static List<String> fromDeluxe(List<String> actions) {
        List<String> result = new ArrayList<>();
        if (actions == null) return result;
        for (String action : actions) {
            String normalized = normalize(action);
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
    }

    public static List<String> toDeluxe(List<String> actions) {
        List<String> result = new ArrayList<>();
        if (actions == null) return result;
        for (String raw : actions) {
            String action = normalize(raw);
            if (action.isBlank()) continue;
            int separator = action.indexOf(':');
            String type = separator < 0 ? action.toLowerCase(Locale.ROOT) : action.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = separator < 0 ? "" : action.substring(separator + 1).trim();
            result.add(switch (type) {
                case "tell", "message", "msg" -> "[message] " + value;
                case "command", "cmd" -> "[player] " + stripSlash(value);
                case "console", "op" -> "[console] " + stripSlash(value);
                case "close" -> "[close]";
                case "menu", "open" -> "[open] " + value;
                case "sound" -> "[sound] " + value;
                default -> action;
            });
        }
        return result;
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String action = raw.trim();
        if (action.isEmpty()) return "";

        String lower = action.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[player]")) {
            String value = action.substring(8).trim();
            String valueLower = value.toLowerCase(Locale.ROOT);
            if (valueLower.startsWith("msg:")) return "tell:" + value.substring(4).trim();
            if (valueLower.startsWith("tell:")) return "tell:" + value.substring(5).trim();
            if (valueLower.startsWith("cmd:")) return "command:" + value.substring(4).trim();
            return "command:" + value;
        }
        if (lower.startsWith("[console]")) return "console:" + action.substring(9).trim();
        if (lower.startsWith("[message]")) return "message:" + action.substring(9).trim();
        if (lower.startsWith("[sound]")) return "sound:" + action.substring(7).trim();
        if (lower.startsWith("[open]")) return "menu:" + action.substring(6).trim();
        if (lower.equals("[close]") || action.equals("关闭")) return "close";

        action = replacePrefix(action, "控制台命令:", "console:");
        action = replacePrefix(action, "控制台:", "console:");
        action = replacePrefix(action, "玩家命令:", "command:");
        action = replacePrefix(action, "命令:", "command:");
        action = replacePrefix(action, "消息:", "message:");
        action = replacePrefix(action, "提示:", "message:");
        action = replacePrefix(action, "聊天:", "chat:");
        action = replacePrefix(action, "打开菜单:", "menu:");
        action = replacePrefix(action, "菜单:", "menu:");
        action = replacePrefix(action, "声音:", "sound:");

        int separator = action.indexOf(':');
        if (separator < 0) return action.equalsIgnoreCase("close") ? "close" : action;
        String type = action.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        return type + ":" + action.substring(separator + 1).trim();
    }

    public static boolean isSupported(String raw) {
        String action = normalize(raw);
        if (action.isEmpty() || action.length() > 512) return false;
        if (action.equals("close")) return true;
        int separator = action.indexOf(':');
        return separator > 0 && separator < action.length() - 1
            && SUPPORTED_TYPES.contains(action.substring(0, separator));
    }

    public static String replacePlaceholders(String text, Player player) {
        if (text == null || player == null) return text == null ? "" : text;
        return text
            .replace("{player}", player.getName())
            .replace("%player%", player.getName())
            .replace("{uuid}", player.getUniqueId().toString())
            .replace("%uuid%", player.getUniqueId().toString());
    }

    private static String replacePrefix(String action, String prefix, String replacement) {
        return action.startsWith(prefix) ? replacement + action.substring(prefix.length()).trim() : action;
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
