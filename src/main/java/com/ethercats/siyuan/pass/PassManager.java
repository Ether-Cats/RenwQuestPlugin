package com.ethercats.siyuan.pass;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.DatabaseManager;
import com.ethercats.siyuan.pass.reward.RewardExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PassManager {
    
    private final SiYuanPlugin plugin;
    private final DatabaseManager db;
    private PassConfig passConfig;
    private final Map<UUID, PlayerPassData> playerDataCache = new ConcurrentHashMap<>();
    
    public PassManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        reload();
    }
    
    public void reload() {
        String passId = plugin.getConfig().getString("pass.default-pass", "default");
        passConfig = PassConfig.load(plugin, passId);
        if (passConfig == null) {
            plugin.getLogger().severe("默认通行证加载失败！");
        }
    }
    
    public PlayerPassData getPlayerData(UUID uuid) {
        return playerDataCache.computeIfAbsent(uuid, this::loadFromDB);
    }
    
    private PlayerPassData loadFromDB(UUID uuid) {
        String seasonId = plugin.getSeasonManager().getActiveSeason() != null 
                        ? plugin.getSeasonManager().getActiveSeason().getId() : "none";
        
        PlayerPassData loaded = db.query(
            "SELECT * FROM sy_player_pass WHERE uuid=? AND season_id=?",
            rs -> {
                if (rs.next()) {
                    TierType tier = TierType.fromString(rs.getString("tier"));
                    int level = rs.getInt("level");
                    long exp = rs.getLong("experience");
                    long totalExp = rs.getLong("total_exp_earned");
                    
                    // 加载已领取奖励
                    Set<String> claimed = new HashSet<>();
                    db.query("SELECT level, tier FROM sy_claimed_rewards WHERE uuid=? AND season_id=? AND pass_id=?",
                        rs2 -> {
                            while (rs2.next()) {
                                claimed.add(rs2.getInt("level") + "-" + rs2.getString("tier"));
                            }
                            return null;
                        }, uuid.toString(), seasonId, passConfig.getId());
                    
                    return new PlayerPassData(uuid, seasonId, passConfig.getId(), tier, level, exp, totalExp, claimed);
                } else {
                    // 新玩家
                    PlayerPassData data = new PlayerPassData(uuid, seasonId, passConfig.getId(), 
                                                            TierType.FREE, 1, 0, 0, new HashSet<>());
                    savePlayerData(data);
                    return data;
                }
            },
            uuid.toString(), seasonId
        );
        if (loaded == null) {
            throw new IllegalStateException("无法读取玩家通行证数据，拒绝用空数据覆盖原记录: " + uuid);
        }
        return loaded;
    }
    
    public void savePlayerData(PlayerPassData data) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            savePlayerDataNow(data);
        });
    }

    public void saveAll() {
        playerDataCache.values().forEach(this::savePlayerDataNow);
    }

    private boolean savePlayerDataNow(PlayerPassData data) {
        return db.execute(
            "INSERT INTO sy_player_pass (uuid, season_id, pass_id, tier, level, experience, total_exp_earned, last_update) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE tier=?, level=?, experience=?, total_exp_earned=?, last_update=?",
            data.getUuid().toString(), data.getSeasonId(), data.getPassId(), data.getTier().name(),
            data.getLevel(), data.getExperience(), data.getTotalExpEarned(), System.currentTimeMillis(),
            data.getTier().name(), data.getLevel(), data.getExperience(), data.getTotalExpEarned(), System.currentTimeMillis()
        ) >= 0;
    }
    
    public void addExperience(Player player, long amount) {
        if (passConfig == null || amount <= 0 || plugin.getSeasonManager().isTransitioning()) return;
        PlayerPassData data = getPlayerData(player.getUniqueId());
        int levelUps = data.addExp(amount, passConfig);
        
        if (levelUps > 0) {
            plugin.getMessageService().send(player, "pass.level-up", data.getLevel());
            player.sendTitle("§6✦ §e等级提升 §6✦", "§fLv." + data.getLevel(), 10, 40, 10);
        }
        
        savePlayerData(data);
    }
    
    public void claimReward(Player player, int level, TierType tier) {
        if (passConfig == null) return;
        PlayerPassData data = getPlayerData(player.getUniqueId());
        synchronized (data) {
            claimRewardLocked(player, level, tier, data);
        }
    }

    private void claimRewardLocked(Player player, int level, TierType tier, PlayerPassData data) {
        if (level < 1 || level > passConfig.getMaxLevel() || !data.canClaimReward(level, tier)) {
            if (data.getLevel() < level) {
                plugin.getMessageService().send(player, "pass.not-completed");
            } else if (data.hasClaimed(level, tier)) {
                plugin.getMessageService().send(player, "pass.already-claimed");
            } else {
                plugin.getMessageService().send(player, "pass.no-tier");
            }
            return;
        }
        
        int inserted = db.execute(
            "INSERT IGNORE INTO sy_claimed_rewards (uuid, season_id, pass_id, level, tier, claimed_at) VALUES (?, ?, ?, ?, ?, ?)",
            player.getUniqueId().toString(), data.getSeasonId(), data.getPassId(), level, tier.name(), System.currentTimeMillis()
        );
        if (inserted != 1) {
            if (inserted == 0) plugin.getMessageService().send(player, "pass.already-claimed");
            else player.sendMessage("§c奖励记录保存失败，请稍后重试");
            return;
        }

        // 执行奖励
        List<String> rewards = passConfig.getRewardsForLevel(level, tier);
        if (rewards.isEmpty()) {
            db.execute("DELETE FROM sy_claimed_rewards WHERE uuid=? AND season_id=? AND pass_id=? AND level=? AND tier=?",
                player.getUniqueId().toString(), data.getSeasonId(), data.getPassId(), level, tier.name());
            plugin.getMessageService().send(player, "pass.no-reward");
            return;
        }
        for (String reward : rewards) {
            RewardExecutor.executeReward(player, reward, plugin);
        }
        
        data.claimReward(level, tier);
        
        plugin.getMessageService().send(player, "pass.reward-claim", level, tier.name());
        savePlayerData(data);
    }
    
    public void upgradeTier(Player player, TierType newTier) {
        PlayerPassData data = getPlayerData(player.getUniqueId());
        if (data.getTier().ordinal() >= newTier.ordinal()) {
            player.sendMessage("§c你已经拥有更高或相同档位的通行证");
            return;
        }
        
        double price = plugin.getConfig().getDouble("pass.tier-prices." + newTier.name().toLowerCase(), 0);
        if (price > 0 && !plugin.getEconomyService().sink(player, price, "PASS_UPGRADE")) {
            plugin.getMessageService().send(player, "pass.insufficient-funds", 
                                           plugin.getEconomyService().format(price));
            return;
        }
        
        TierType previousTier = data.getTier();
        data.setTier(newTier);
        if (!savePlayerDataNow(data)) {
            data.setTier(previousTier);
            if (price > 0) plugin.getEconomyService().refund(player, price, "PASS_UPGRADE_FAILED");
            player.sendMessage("§c档位保存失败，费用已退回");
            return;
        }
        plugin.getMessageService().send(player, "pass.tier-upgraded", newTier.name());
    }
    
    public void setLevel(Player player, int level) {
        PlayerPassData data = getPlayerData(player.getUniqueId());
        int maxLevel = passConfig == null ? 100 : passConfig.getMaxLevel();
        data.setLevel(Math.max(1, Math.min(maxLevel, level)));
        data.setExperience(0);
        savePlayerData(data);
    }
    
    public void openGUI(Player player) {
        plugin.getGuiManager().openPass(player);
    }

    public PassConfig getPassConfig() { return passConfig; }

    public void clearCache() { playerDataCache.clear(); }
}
