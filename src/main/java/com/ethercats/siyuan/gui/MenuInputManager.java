package com.ethercats.siyuan.gui;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles the short-lived chat and writable-book sessions used by GFMenu actions. */
public final class MenuInputManager implements Listener {
    private static final long CATCHER_TIMEOUT_MILLIS = 30_000L;
    private static final long BOOK_TIMEOUT_MILLIS = 120_000L;

    private final SiYuanPlugin plugin;
    private final DynamicMenuManager menuManager;
    private final Map<UUID, InputSession> catcherSessions = new ConcurrentHashMap<>();
    private final Map<UUID, InputSession> bookSessions = new ConcurrentHashMap<>();
    private final BukkitTask timeoutTask;

    public MenuInputManager(SiYuanPlugin plugin, DynamicMenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
        this.timeoutTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireSessions, 20L * 15, 20L * 15);
    }

    public void startCatcher(Player player, String action) {
        InputSpec spec = InputSpec.parse(action, "catcher", "default");
        if (spec == null) return;

        cancelInputSessions(player, false);
        catcherSessions.put(player.getUniqueId(), new InputSession(spec.id(), spec.endActions(), spec.cancelActions()));
        menuManager.runActions(player, spec.startActions());
        player.sendMessage(color("&a请在聊天栏输入内容，输入 &ecancel &a或 &e取消 &a可放弃。"));
    }

    public void startBook(Player player, String action) {
        InputSpec spec = InputSpec.parse(action, "book", "book");
        if (spec == null) return;

        cancelInputSessions(player, false);
        bookSessions.put(player.getUniqueId(), new InputSession(spec.id(), spec.endActions(), spec.cancelActions()));
        player.sendMessage(color(spec.prompt()));

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&8输入: &f" + spec.id()));
            book.setItemMeta(meta);
        }
        player.openBook(book);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!catcherSessions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        String input = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> finishCatcher(player, input));
    }

    @EventHandler
    public void onPlayerEditBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        InputSession session = bookSessions.remove(player.getUniqueId());
        if (session == null) return;
        finishSession(player, session, String.join(System.lineSeparator(), event.getNewBookMeta().getPages()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelInputSessions(event.getPlayer(), false);
    }

    public void shutdown() {
        timeoutTask.cancel();
        catcherSessions.clear();
        bookSessions.clear();
    }

    private void finishCatcher(Player player, String input) {
        InputSession session = catcherSessions.remove(player.getUniqueId());
        if (session == null) return;
        if (input.equalsIgnoreCase("cancel") || input.equals("取消")) {
            cancelSession(player, session, true);
            return;
        }
        finishSession(player, session, input);
    }

    private void finishSession(Player player, InputSession session, String input) {
        for (String action : session.endActions()) {
            menuManager.executeAction(player, replaceInput(action, input, session.id()));
        }
    }

    private void cancelInputSessions(Player player, boolean notify) {
        InputSession catcher = catcherSessions.remove(player.getUniqueId());
        if (catcher != null) cancelSession(player, catcher, notify);
        InputSession book = bookSessions.remove(player.getUniqueId());
        if (book != null) cancelSession(player, book, notify);
    }

    private void cancelSession(Player player, InputSession session, boolean notify) {
        menuManager.runActions(player, session.cancelActions());
        if (notify) player.sendMessage(color("&c输入已取消。"));
    }

    private void expireSessions() {
        long now = System.currentTimeMillis();
        expire(catcherSessions, CATCHER_TIMEOUT_MILLIS, now);
        expire(bookSessions, BOOK_TIMEOUT_MILLIS, now);
    }

    private void expire(Map<UUID, InputSession> sessions, long timeoutMillis, long now) {
        for (Map.Entry<UUID, InputSession> entry : sessions.entrySet()) {
            InputSession session = entry.getValue();
            if (now - session.startedAt() < timeoutMillis || !sessions.remove(entry.getKey(), session)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) player.sendMessage(color("&c输入会话已超时。"));
        }
    }

    private static String replaceInput(String action, String input, String id) {
        String value = input == null ? "" : input.replace('\r', ' ').replace('\n', ' ').trim();
        return action
            .replace("%book_input%", value)
            .replace("{book_input}", value)
            .replace("%book_input_" + id + "%", value)
            .replace("{input}", value)
            .replace("%input%", value)
            .replace("%player_input%", value);
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private record InputSession(String id, List<String> endActions, List<String> cancelActions, long startedAt) {
        private InputSession(String id, List<String> endActions, List<String> cancelActions) {
            this(id, List.copyOf(endActions), List.copyOf(cancelActions), System.currentTimeMillis());
        }
    }

    private record InputSpec(String id, String prompt, List<String> startActions, List<String> endActions,
                             List<String> cancelActions) {
        private static InputSpec parse(String raw, String expectedType, String fallbackId) {
            String action = MenuActionCodec.normalize(raw);
            int separator = action.indexOf(':');
            if (separator <= 0 || !expectedType.equals(action.substring(0, separator))) return null;

            String[] segments = action.substring(separator + 1).split("\\|", -1);
            String id = segments.length == 0 || segments[0].isBlank() ? fallbackId : segments[0].trim();
            String prompt = "&e请在书本中输入内容，然后点击完成。";
            List<String> startActions = new ArrayList<>();
            List<String> endActions = new ArrayList<>();
            List<String> cancelActions = new ArrayList<>();

            for (int index = 1; index < segments.length; index++) {
                String segment = segments[index].trim();
                int assignment = segment.indexOf('=');
                if (assignment <= 0) continue;
                String key = segment.substring(0, assignment).trim().toLowerCase(Locale.ROOT);
                String value = segment.substring(assignment + 1).trim();
                if (value.isEmpty()) continue;
                switch (key) {
                    case "prompt" -> prompt = value;
                    case "start" -> startActions.add(value);
                    case "end" -> endActions.add(value);
                    case "cancel" -> cancelActions.add(value);
                    default -> {
                    }
                }
            }
            return new InputSpec(id, prompt, List.copyOf(startActions), List.copyOf(endActions), List.copyOf(cancelActions));
        }
    }
}
