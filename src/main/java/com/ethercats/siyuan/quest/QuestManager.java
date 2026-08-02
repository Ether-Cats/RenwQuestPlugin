package com.ethercats.siyuan.quest;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.DatabaseManager;
import com.ethercats.siyuan.pass.reward.RewardExecutor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class QuestManager {
    
    private final SiYuanPlugin plugin;
    private final DatabaseManager db;
    private final Gson gson = new Gson();
    private Map<String, QuestConfig> questConfigs;
    private final Map<UUID, PlayerQuestData> playerDataCache = new ConcurrentHashMap<>();
    private final AtomicBoolean resetInProgress = new AtomicBoolean();
    
    public QuestManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        reload();
    }
    
    public void reload() {
        questConfigs = QuestConfig.loadAll(plugin);
        if (questConfigs == null) questConfigs = new HashMap<>();
    }
    
    public PlayerQuestData getPlayerData(UUID uuid) {
        return playerDataCache.computeIfAbsent(uuid, this::loadFromDB);
    }
    
    private PlayerQuestData loadFromDB(UUID uuid) {
        String seasonId = plugin.getSeasonManager().getActiveSeason() != null 
                        ? plugin.getSeasonManager().getActiveSeason().getId() : "none";
        PlayerQuestData data = new PlayerQuestData(uuid, seasonId);
        
        Boolean loaded = db.query(
            "SELECT * FROM sy_quest_progress WHERE uuid=? AND season_id=? ORDER BY id ASC",
            rs -> {
                while (rs.next()) {
                    String questId = rs.getString("quest_id");
                    QuestType type = QuestType.fromString(rs.getString("quest_type"));
                    String progressJson = rs.getString("progress_json");
                    QuestStatus status;
                    try { status = QuestStatus.valueOf(rs.getString("status")); }
                    catch (IllegalArgumentException ex) { status = QuestStatus.IN_PROGRESS; }
                    long startedAt = rs.getLong("started_at");
                    Long completedAt = rs.getObject("completed_at", Long.class);
                    String resetDate = rs.getString("reset_date");
                    String expectedReset = resetKey(type);
                    if ((type == QuestType.DAILY || type == QuestType.WEEKLY) && !expectedReset.equals(resetDate)) continue;
                    if (type == QuestType.SEASONAL && !seasonId.equals(resetDate)) continue;
                    
                    Map<Integer, Integer> progressMap = gson.fromJson(progressJson,
                        new TypeToken<Map<Integer, Integer>>(){}.getType());
                    if (progressMap == null) progressMap = new HashMap<>();
                    
                    QuestProgress prog = new QuestProgress(questId, type, progressMap, 
                                                          status, startedAt, completedAt, resetDate);
                    data.getAllQuests().put(questId, prog);
                }
                return Boolean.TRUE;
            },
            uuid.toString(), seasonId
        );
        if (loaded == null) throw new IllegalStateException("无法读取玩家任务数据，拒绝用空数据覆盖原记录: " + uuid);
        return data;
    }
    
    public void savePlayerData(PlayerQuestData data) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            savePlayerDataNow(data);
        });
    }

    public void saveAll() {
        playerDataCache.values().forEach(this::savePlayerDataNow);
    }

    private void savePlayerDataNow(PlayerQuestData data) {
        for (QuestProgress prog : data.getAllQuests().values()) {
            String progressJson = gson.toJson(prog.getObjectiveProgress());
            db.execute(
                "INSERT INTO sy_quest_progress (uuid, quest_id, quest_type, season_id, progress_json, status, started_at, completed_at, reset_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE progress_json=?, status=?, completed_at=?",
                data.getUuid().toString(), prog.getQuestId(), prog.getType().name(), data.getSeasonId(),
                progressJson, prog.getStatus().name(), prog.getStartedAt(), prog.getCompletedAt(), prog.getResetDate(),
                progressJson, prog.getStatus().name(), prog.getCompletedAt()
            );
        }
    }
    
    public void addProgress(Player player, String conditionType, String target, int amount) {
        if (amount <= 0 || resetInProgress.get() || plugin.getSeasonManager().isTransitioning()) return;
        PlayerQuestData data = getPlayerData(player.getUniqueId());
        for (QuestConfig config : questConfigs.values()) {
            if (!isAssigned(player.getUniqueId(), config)) continue;
            String resetDate = resetKey(config.getType());
            QuestProgress prog = data.getProgress(config.getId());
            if (prog != null && !resetDate.equals(prog.getResetDate()) && (config.getType() == QuestType.DAILY || config.getType() == QuestType.WEEKLY)) {
                data.getAllQuests().remove(config.getId());
                prog = null;
            }
            if (prog != null && prog.getStatus() != QuestStatus.IN_PROGRESS) continue;
            
            if (prog == null) {
                prog = data.getOrCreateProgress(config, resetDate);
            }
            
            for (int i = 0; i < config.getObjectives().size(); i++) {
                QuestConfig.Objective obj = config.getObjectives().get(i);
                if (obj.getConditionType() != null && obj.getTarget() != null
                    && obj.getConditionType().equalsIgnoreCase(conditionType) &&
                    (obj.getTarget().equalsIgnoreCase(target) || obj.getTarget().equalsIgnoreCase("ANY"))) {
                    
                    int oldProg = prog.getProgress(i);
                    prog.addProgress(i, amount);
                    int newProg = prog.getProgress(i);
                    
                    if (newProg >= obj.getAmount() && oldProg < obj.getAmount()) {
                        // 目标完成
                        player.sendMessage("§a✔ §7目标完成: §f" + obj.getTarget() + " §e" + newProg + "/" + obj.getAmount());
                    }
                    
                    // 检查任务是否全部完成
                    if (prog.isCompleted(config) && prog.getStatus() == QuestStatus.IN_PROGRESS) {
                        completeQuest(player, config, prog);
                    }
                }
            }
        }
        
        savePlayerData(data);
    }
    
    private void completeQuest(Player player, QuestConfig config, QuestProgress prog) {
        prog.markCompleted();
        plugin.getMessageService().send(player, "quest.complete", config.getName());
        player.sendMessage("§e打开任务界面点击任务领取奖励");
    }

    /**
     * Claims a completed quest exactly once for the current player cache.
     * The conditional database update is the cross-thread guard; rewards are
     * deliberately granted only after the status transition is accepted.
     */
    public void claimQuest(Player player, String questId) {
        QuestConfig config = questConfigs.get(questId);
        if (config == null || !isAssigned(player.getUniqueId(), config)) {
            player.sendMessage("§c任务不存在或当前周期未分配给你");
            return;
        }
        PlayerQuestData data = getPlayerData(player.getUniqueId());
        QuestProgress progress = data.getProgress(questId);
        if (progress == null || progress.getStatus() != QuestStatus.COMPLETED) {
            player.sendMessage("§c该任务尚未完成或已经领取");
            return;
        }
        synchronized (data) {
            if (progress.getStatus() != QuestStatus.COMPLETED) return;
            // A completion may have been created in the same tick as the GUI
            // click; make sure its row exists before the conditional claim.
            savePlayerDataNow(data);
            int updated = db.execute(
                "UPDATE sy_quest_progress SET status='CLAIMED' WHERE uuid=? AND quest_id=? AND season_id=? AND reset_date=? AND status='COMPLETED'",
                player.getUniqueId().toString(), questId, data.getSeasonId(), progress.getResetDate()
            );
            if (updated != 1) {
                player.sendMessage("§c任务领取状态保存失败，请稍后重试");
                return;
            }
            progress.markClaimed();
            grantRewards(player, config);
            savePlayerData(data);
        }
    }

    private void grantRewards(Player player, QuestConfig config) {
        double multiplier = plugin.getConfig().getDouble("quest.exp-multiplier", 1.0);
        long exp = Math.max(0, Math.round(config.getExperience() * multiplier));
        if (exp > 0) {
            plugin.getPassManager().addExperience(player, exp);
            plugin.getMessageService().sendRaw(player, "quest.exp-gained", exp);
        }
        for (String reward : config.getRewards()) RewardExecutor.executeReward(player, reward, plugin);
    }

    public List<QuestConfig> getAssignedQuests(UUID uuid, QuestType type) {
        return questConfigs.values().stream()
            .filter(config -> config.getType() == type && isAssigned(uuid, config))
            .sorted(Comparator.comparingInt(QuestConfig::getPriority).thenComparing(QuestConfig::getId))
            .toList();
    }

    private boolean isAssigned(UUID uuid, QuestConfig config) {
        int limit = assignmentLimit(config.getType());
        if (limit <= 0) return true;
        List<QuestConfig> pool = questConfigs.values().stream()
            .filter(other -> other.getType() == config.getType())
            .sorted(Comparator.comparingLong(other -> assignmentScore(uuid, other)))
            .toList();
        return pool.stream().limit(limit).anyMatch(other -> other.getId().equals(config.getId()));
    }

    private long assignmentScore(UUID uuid, QuestConfig config) {
        return (uuid.toString() + ":" + resetKey(config.getType()) + ":" + config.getId()).hashCode() & 0xffffffffL;
    }

    private int assignmentLimit(QuestType type) {
        return switch (type) {
            case DAILY -> plugin.getConfig().getInt("quest.assignment-limits.daily", 10);
            case WEEKLY -> plugin.getConfig().getInt("quest.assignment-limits.weekly", 30);
            case SEASONAL -> plugin.getConfig().getInt("quest.assignment-limits.seasonal", 0);
            case STORY -> plugin.getConfig().getInt("quest.assignment-limits.story", 0);
            case CHALLENGE -> plugin.getConfig().getInt("quest.assignment-limits.challenge", 0);
        };
    }
    
    public void runDailyReset() {
        if (!resetInProgress.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (db.execute("DELETE FROM sy_quest_progress WHERE quest_type='DAILY'") < 0) throw new IllegalStateException("数据库删除失败");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    playerDataCache.values().forEach(data -> data.removeType(QuestType.DAILY));
                    resetInProgress.set(false);
                    plugin.getMessageService().broadcast("quest.daily-reset");
                });
            } catch (RuntimeException ex) {
                resetInProgress.set(false);
                plugin.getLogger().warning("[Quest] 每日重置失败: " + ex.getMessage());
            }
        });
    }

    public void runWeeklyReset() {
        if (!resetInProgress.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (db.execute("DELETE FROM sy_quest_progress WHERE quest_type='WEEKLY'") < 0) throw new IllegalStateException("数据库删除失败");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    playerDataCache.values().forEach(data -> data.removeType(QuestType.WEEKLY));
                    resetInProgress.set(false);
                    plugin.getMessageService().broadcast("quest.weekly-reset");
                });
            } catch (RuntimeException ex) {
                resetInProgress.set(false);
                plugin.getLogger().warning("[Quest] 每周重置失败: " + ex.getMessage());
            }
        });
    }
    
    public void openGUI(Player player, QuestType type) {
        plugin.getGuiManager().openQuests(player, type);
    }

    public Map<String, QuestConfig> getQuestConfigs() { return questConfigs; }

    public void clearCache() { playerDataCache.clear(); }

    private String resetKey(QuestType type) {
        return switch (type) {
            case DAILY -> LocalDate.now().toString();
            case WEEKLY -> LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case SEASONAL -> plugin.getSeasonManager().getActiveSeason() == null
                ? "none" : plugin.getSeasonManager().getActiveSeason().getId();
            case STORY, CHALLENGE -> "persistent";
        };
    }
}
