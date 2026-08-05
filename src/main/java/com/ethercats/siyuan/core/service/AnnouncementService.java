package com.ethercats.siyuan.core.service;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/** Sends a small, opt-in rotation of server announcements on the main thread. */
public final class AnnouncementService {
    private static final int MIN_INTERVAL_SECONDS = 10;
    private static final int MAX_INTERVAL_SECONDS = 86_400;

    private final SiYuanPlugin plugin;
    private BukkitTask task;
    private List<String> messages = List.of();
    private int nextMessageIndex;

    public AnnouncementService(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("announcements");
        if (config == null || !config.getBoolean("enabled", false)) return;

        messages = config.getStringList("messages").stream()
            .filter(message -> message != null && !message.isBlank())
            .limit(100)
            .toList();
        if (messages.isEmpty()) {
            plugin.getLogger().warning("[Announcement] 已启用但没有可发送的公告内容");
            return;
        }

        int configuredInterval = config.getInt("interval-seconds", 300);
        int intervalSeconds = normalizeIntervalSeconds(configuredInterval);
        if (configuredInterval != intervalSeconds) {
            plugin.getLogger().warning("[Announcement] interval-seconds 已限制为 " + intervalSeconds + " 秒");
        }
        nextMessageIndex = 0;
        task = Bukkit.getScheduler().runTaskTimer(
            plugin, this::broadcastNext, intervalSeconds * 20L, intervalSeconds * 20L);
        plugin.getLogger().info("[Announcement] 已启用 " + messages.size() + " 条公告，每 " + intervalSeconds + " 秒轮播");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        messages = List.of();
        nextMessageIndex = 0;
    }

    public boolean isEnabled() {
        return task != null;
    }

    public int getMessageCount() {
        return messages.size();
    }

    static int normalizeIntervalSeconds(int requested) {
        return Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, requested));
    }

    private void broadcastNext() {
        if (messages.isEmpty()) return;
        String message = messages.get(nextMessageIndex++ % messages.size())
            .replace("{online}", Integer.toString(Bukkit.getOnlinePlayers().size()))
            .replace("{max_players}", Integer.toString(Bukkit.getMaxPlayers()));
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
