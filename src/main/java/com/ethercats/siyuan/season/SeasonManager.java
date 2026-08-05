package com.ethercats.siyuan.season;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.DatabaseManager;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class SeasonManager {
    
    private final SiYuanPlugin plugin;
    private final DatabaseManager db;
    private Season activeSeason;
    private final AtomicBoolean transitioning = new AtomicBoolean();
    
    public SeasonManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        loadActiveSeason();
    }
    
    public void loadActiveSeason() {
        java.util.Optional<Season> loaded = db.query("SELECT * FROM sy_seasons WHERE active=1 LIMIT 1", rs -> {
            if (rs.next()) {
                return java.util.Optional.of(Season.fromResultSet(rs));
            }
            return java.util.Optional.empty();
        });
        if (loaded == null) {
            plugin.getLogger().severe("[Season] 活跃赛季读取失败，保留现有内存状态");
            return;
        }
        activeSeason = loaded.orElse(null);
        
        if (activeSeason != null) {
            plugin.getLogger().info("[Season] 当前赛季: " + activeSeason.getName());
        }
    }
    
    public void startSeason(String name) {
        if (!transitioning.compareAndSet(false, true)) {
            plugin.getLogger().warning("赛季切换正在进行，请勿重复操作");
            return;
        }
        String seasonId = "season_" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        Season oldSeason = activeSeason;
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deactivated = 1;
            if (oldSeason != null) {
                deactivated = db.execute("UPDATE sy_seasons SET active=0, end_time=? WHERE id=?", now, oldSeason.getId());
            }
            int inserted = deactivated < 0 || (oldSeason != null && deactivated != 1) ? -1 : db.execute(
                "INSERT INTO sy_seasons (id, name, start_time, active, created_at) VALUES (?, ?, ?, 1, ?)",
                seasonId, name, now, now
            );
            if (inserted != 1 && oldSeason != null) {
                db.execute("UPDATE sy_seasons SET active=1, end_time=NULL WHERE id=?", oldSeason.getId());
            }
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                transitioning.set(false);
                if (inserted != 1) {
                    activeSeason = oldSeason;
                    plugin.getLogger().severe("新赛季写入数据库失败，未切换赛季");
                    return;
                }
                activeSeason = new Season(seasonId, name, now, null, true);
                plugin.getPassManager().clearCache();
                plugin.getQuestManager().clearCache();
                if (oldSeason != null) plugin.getMessageService().broadcast("season.ended", oldSeason.getName());
                plugin.getMessageService().broadcast("season.started", name);
            });
        });
    }
    
    public void endSeason() {
        if (!transitioning.compareAndSet(false, true)) {
            plugin.getLogger().warning("赛季切换正在进行，请勿重复操作");
            return;
        }
        if (activeSeason == null) {
            transitioning.set(false);
            plugin.getLogger().warning("没有活跃赛季可结束");
            return;
        }
        
        String seasonId = activeSeason.getId();
        String seasonName = activeSeason.getName();
        long seasonStartTime = activeSeason.getStartTime();
        long now = System.currentTimeMillis();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 更新赛季状态
            int updated = db.execute("UPDATE sy_seasons SET active=0, end_time=? WHERE id=?", now, seasonId);
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                transitioning.set(false);
                if (updated != 1) {
                    activeSeason = new Season(seasonId, seasonName, seasonStartTime, null, true);
                    plugin.getLogger().severe("赛季结束状态写入失败，已恢复内存状态");
                    return;
                }
                activeSeason = null;
                plugin.getMessageService().broadcast("season.ended", seasonName);
                plugin.getPassManager().clearCache();
                plugin.getQuestManager().clearCache();
            });
        });
    }
    
    public List<String> getLeaderboard(int topN) {
        if (activeSeason == null) return List.of();
        
        List<String> result = new ArrayList<>();
        db.query(
            "SELECT p.uuid, pl.name, p.level, p.experience FROM sy_player_pass p " +
            "LEFT JOIN sy_players pl ON p.uuid=pl.uuid " +
            "WHERE p.season_id=? ORDER BY p.level DESC, p.experience DESC LIMIT ?",
            rs -> {
                int rank = 1;
                while (rs.next()) {
                    String name = rs.getString("name");
                    int level = rs.getInt("level");
                    long exp = rs.getLong("experience");
                    result.add(String.format("#%d §e%s §7- §6Lv.%d §7(%d exp)", rank++, name, level, exp));
                }
                return null;
            },
            activeSeason.getId(), topN
        );
        
        return result;
    }
    
    public Season getActiveSeason() {
        return activeSeason;
    }

    public boolean isTransitioning() { return transitioning.get(); }
}
