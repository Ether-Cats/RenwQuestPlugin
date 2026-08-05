package com.ethercats.siyuan.core.service;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in operational audit log. Events are queued on the server thread and
 * flushed asynchronously; IP addresses, chat text, inventories, and movement
 * are intentionally never recorded.
 */
public final class ActivityAuditService implements Listener {
    private static final int MIN_QUEUE_SIZE = 100;
    private static final int MAX_QUEUE_SIZE = 10_000;
    private static final int MIN_FLUSH_SECONDS = 1;
    private static final int MAX_FLUSH_SECONDS = 60;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SiYuanPlugin plugin;
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong lastDropWarningAt = new AtomicLong();
    private final Object flushLock = new Object();
    private volatile BlockingQueue<String> records = new ArrayBlockingQueue<>(2_000);
    private volatile boolean enabled;
    private volatile boolean includeJoins;
    private volatile boolean includeBlocks;
    private BukkitTask flushTask;

    public ActivityAuditService(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        stop();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("activity-audit");
        if (config == null || !config.getBoolean("enabled", false)) return;

        int queueSize = normalizeQueueSize(config.getInt("queue-size", 2_000));
        int flushSeconds = normalizeFlushSeconds(config.getInt("flush-seconds", 5));
        records = new ArrayBlockingQueue<>(queueSize);
        includeJoins = config.getBoolean("include-joins", true);
        includeBlocks = config.getBoolean("include-blocks", true);
        droppedEvents.set(0L);
        lastDropWarningAt.set(0L);
        enabled = true;
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::flush, flushSeconds * 20L, flushSeconds * 20L);
        plugin.getLogger().info("[Audit] 已启用活动审计，队列容量 " + queueSize + "，每 " + flushSeconds + " 秒落盘");
    }

    public synchronized void stop() {
        enabled = false;
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flush();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getQueuedEventCount() {
        return records.size();
    }

    public long getDroppedEventCount() {
        return droppedEvents.get();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (enabled && includeJoins) enqueue("JOIN", "player=" + safe(event.getPlayer().getName()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (enabled && includeJoins) enqueue("QUIT", "player=" + safe(event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled || !includeBlocks) return;
        enqueueBlock("BREAK", event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled || !includeBlocks) return;
        enqueueBlock("PLACE", event.getPlayer(), event.getBlockPlaced());
    }

    static int normalizeQueueSize(int requested) {
        return Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, requested));
    }

    static int normalizeFlushSeconds(int requested) {
        return Math.max(MIN_FLUSH_SECONDS, Math.min(MAX_FLUSH_SECONDS, requested));
    }

    private void enqueueBlock(String type, Player player, Block block) {
        enqueue(type, "player=" + safe(player.getName())
            + " world=" + safe(block.getWorld().getName())
            + " x=" + block.getX() + " y=" + block.getY() + " z=" + block.getZ()
            + " block=" + block.getType().name());
    }

    private void enqueue(String type, String fields) {
        String record = TIMESTAMP.format(OffsetDateTime.now(ZoneOffset.UTC)) + " " + type + " " + fields;
        if (records.offer(record)) return;

        long dropped = droppedEvents.incrementAndGet();
        long now = System.currentTimeMillis();
        long previousWarning = lastDropWarningAt.get();
        if (now - previousWarning >= 60_000L && lastDropWarningAt.compareAndSet(previousWarning, now)) {
            plugin.getLogger().warning("[Audit] 审计队列已满，已丢弃 " + dropped + " 条事件");
        }
    }

    private void flush() {
        synchronized (flushLock) {
            List<String> batch = new ArrayList<>();
            records.drainTo(batch);
            if (batch.isEmpty()) return;

            Path directory = plugin.getDataFolder().toPath().resolve("audit");
            Path file = directory.resolve("activity-" + LocalDate.now(ZoneOffset.UTC) + ".log");
            try {
                Files.createDirectories(directory);
                String body = String.join(System.lineSeparator(), batch) + System.lineSeparator();
                Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().warning("[Audit] 写入审计日志失败，已丢弃 " + batch.size() + " 条事件: " + ex.getMessage());
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace(' ', '_');
    }
}
