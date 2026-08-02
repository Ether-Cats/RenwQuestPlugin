package com.ethercats.siyuan;

import com.ethercats.siyuan.command.SiYuanCommand;
import com.ethercats.siyuan.core.DatabaseManager;
import com.ethercats.siyuan.core.RedisManager;
import com.ethercats.siyuan.core.service.EconomyService;
import com.ethercats.siyuan.core.service.MessageService;
import com.ethercats.siyuan.pass.PassManager;
import com.ethercats.siyuan.quest.QuestManager;
import com.ethercats.siyuan.season.SeasonManager;
import com.ethercats.siyuan.shop.ShopManager;
import com.ethercats.siyuan.waypoint.WaypointManager;
import com.ethercats.siyuan.integration.PlaceholderHook;
import com.ethercats.siyuan.gui.GuiManager;
import com.ethercats.siyuan.gui.DynamicMenuManager;
import com.ethercats.siyuan.gui.MenuEditorManager;
import com.ethercats.siyuan.web.RemoteMenuSyncService;
import com.ethercats.siyuan.core.listener.PlayerLifecycleListener;
import com.ethercats.siyuan.quest.listener.QuestListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;

public class SiYuanPlugin extends JavaPlugin {

    private static SiYuanPlugin instance;

    // Core
    private DatabaseManager db;
    private RedisManager redis;
    private EconomyService economyService;
    private MessageService messageService;

    // Modules
    private SeasonManager seasonManager;
    private PassManager passManager;
    private QuestManager questManager;
    private ShopManager shopManager;
    private WaypointManager waypointManager;
    private GuiManager guiManager;
    private DynamicMenuManager dynamicMenuManager;
    private MenuEditorManager menuEditorManager;
    private RemoteMenuSyncService remoteMenuSyncService;

    // Task IDs
    private int dailyTaskId  = -1;
    private int snapshotTaskId = -1;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        copyBundledResources();

        // 1. Core infrastructure
        db = new DatabaseManager(this);
        if (!db.init()) {
            getLogger().severe("数据库初始化失败，插件已禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        redis = new RedisManager(this);
        redis.init();

        messageService = new MessageService(this);

        economyService = new EconomyService(this, redis, db);
        if (!economyService.setup()) {
            getLogger().severe("Vault 经济初始化失败，插件已禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Business modules
        seasonManager   = new SeasonManager(this);
        passManager     = new PassManager(this);
        questManager    = new QuestManager(this);
        shopManager     = new ShopManager(this);
        waypointManager = new WaypointManager(this);
        guiManager      = new GuiManager(this);
        dynamicMenuManager = new DynamicMenuManager(this);
        menuEditorManager = new MenuEditorManager(this, dynamicMenuManager);
        remoteMenuSyncService = new RemoteMenuSyncService(this);

        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(dynamicMenuManager, this);
        getServer().getPluginManager().registerEvents(menuEditorManager, this);
        getServer().getPluginManager().registerEvents(new QuestListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(this), this);

        // 3. Commands
        SiYuanCommand cmd = new SiYuanCommand(this);
        PluginCommand gcCommand = getCommand("gc");
        if (gcCommand == null) {
            getLogger().severe("命令 /gc 未在 plugin.yml 中注册，插件已禁用！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        gcCommand.setExecutor(cmd);
        gcCommand.setTabCompleter(cmd);

        // 4. Optional integrations
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PlaceholderHook(this).register();
            getLogger().info("[Integration] PlaceholderAPI 已挂钩");
        }

        // 5. Scheduled tasks
        scheduleResetTasks();
        scheduleDailySnapshot();
        remoteMenuSyncService.start();

        getLogger().info("=============================");
        getLogger().info("  siyuan 思渊插件已启动 v" + getDescription().getVersion());
        getLogger().info("  数据库: MySQL | 缓存: " + (redis.isEnabled() ? "Redis" : "关闭"));
        getLogger().info("  经济: " + (economyService.isAvailable() ? "Vault" : "不可用"));
        getLogger().info("=============================");
    }

    @Override
    public void onDisable() {
        if (remoteMenuSyncService != null) remoteMenuSyncService.stop();
        if (menuEditorManager != null) menuEditorManager.shutdown();
        // Cancel tasks
        if (dailyTaskId   > 0) getServer().getScheduler().cancelTask(dailyTaskId);
        if (snapshotTaskId > 0) getServer().getScheduler().cancelTask(snapshotTaskId);
        // Stop queued async saves/metrics before flushing the authoritative
        // in-memory snapshots and closing the connection pools.
        getServer().getScheduler().cancelTasks(this);

        // Flush caches
        if (questManager    != null) questManager.saveAll();
        if (passManager     != null) passManager.saveAll();
        if (shopManager     != null) shopManager.saveAll();
        if (waypointManager != null) waypointManager.saveAll();

        // Close connections
        if (redis != null) redis.close();
        if (db    != null) db.close();

        getLogger().info("siyuan 思渊插件已卸载，数据已保存。");
    }

    // 每日/每周重置任务调度
    private void scheduleResetTasks() {
        int resetHour = Math.max(0, Math.min(23, getConfig().getInt("quest.daily-reset-hour", 0)));
        int resetMinute = Math.max(0, Math.min(59, getConfig().getInt("quest.daily-reset-minute", 0)));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextReset = LocalDate.now().atTime(LocalTime.of(resetHour, resetMinute));
        if (!nextReset.isAfter(now)) nextReset = nextReset.plusDays(1);
        long ticksToReset = Math.max(20, now.until(nextReset, ChronoUnit.SECONDS) * 20);
        long dayTicks = 24L * 60 * 60 * 20;
        DayOfWeek weeklyDay;
        try { weeklyDay = DayOfWeek.valueOf(getConfig().getString("quest.weekly-reset-day", "MONDAY").toUpperCase()); }
        catch (IllegalArgumentException ex) { weeklyDay = DayOfWeek.MONDAY; }
        DayOfWeek configuredWeeklyDay = weeklyDay;

        dailyTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> {
            questManager.runDailyReset();
            if (java.time.LocalDate.now().getDayOfWeek() == configuredWeeklyDay) {
                questManager.runWeeklyReset();
            }
        }, ticksToReset, dayTicks);
    }

    // 每日凌晨 00:05 dump 经济快照
    private void scheduleDailySnapshot() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDate.now().plusDays(1).atTime(0, 5);
        long delayTicks = now.until(next, ChronoUnit.SECONDS) * 20;
        long dayTicks   = 24L * 60 * 60 * 20;

        snapshotTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this,
                () -> getServer().getScheduler().runTaskAsynchronously(this,
                        () -> economyService.dumpDailySnapshot()),
                Math.max(delayTicks, 20), dayTicks);
    }

    // Reload
    public void reload() {
        reloadConfig();
        copyBundledResources();
        messageService.reload();
        passManager.reload();
        questManager.reload();
        shopManager.reload();
        dynamicMenuManager.reload();
        remoteMenuSyncService.start();
        if (dailyTaskId > 0) getServer().getScheduler().cancelTask(dailyTaskId);
        scheduleResetTasks();
    }

    // Getters
    public static SiYuanPlugin getInstance()     { return instance; }
    public DatabaseManager getDb()               { return db; }
    public RedisManager getRedis()               { return redis; }
    public EconomyService getEconomyService()    { return economyService; }
    public MessageService getMessageService()    { return messageService; }
    public SeasonManager getSeasonManager()      { return seasonManager; }
    public PassManager getPassManager()          { return passManager; }
    public QuestManager getQuestManager()        { return questManager; }
    public ShopManager getShopManager()          { return shopManager; }
    public WaypointManager getWaypointManager()  { return waypointManager; }
    public GuiManager getGuiManager()            { return guiManager; }
    public DynamicMenuManager getDynamicMenuManager() { return dynamicMenuManager; }
    public MenuEditorManager getMenuEditorManager() { return menuEditorManager; }
    public RemoteMenuSyncService getRemoteMenuSyncService() { return remoteMenuSyncService; }

    private void copyBundledResources() {
        String[] resources = {
            "languages/zh_CN.yml",
            "passes/default.yml",
            "quests/daily/mine_stone.yml",
            "quests/daily/kill_zombie.yml",
            "quests/weekly/craft_tools.yml",
            "menus/example.yml"
        };
        for (String resource : resources) {
            try {
                saveResource(resource, false);
            } catch (IllegalArgumentException e) {
                getLogger().warning("内置资源不存在: " + resource);
            }
        }
    }
}
