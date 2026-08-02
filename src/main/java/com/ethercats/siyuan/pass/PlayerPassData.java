package com.ethercats.siyuan.pass;

import java.util.*;

public class PlayerPassData {
    private final UUID uuid;
    private String seasonId;
    private String passId;
    private TierType tier;
    private int level;
    private long experience;
    private long totalExpEarned;
    private final Set<String> claimedRewards; // "level-tier" 格式

    public PlayerPassData(UUID uuid, String seasonId, String passId, TierType tier, 
                          int level, long experience, long totalExpEarned, Set<String> claimedRewards) {
        this.uuid = uuid;
        this.seasonId = seasonId;
        this.passId = passId;
        this.tier = tier;
        this.level = level;
        this.experience = experience;
        this.totalExpEarned = totalExpEarned;
        this.claimedRewards = claimedRewards != null ? claimedRewards : new HashSet<>();
    }

    public int addExp(long amount, PassConfig config) {
        if (amount <= 0 || config == null) return 0;
        this.totalExpEarned = Long.MAX_VALUE - totalExpEarned < amount
            ? Long.MAX_VALUE : totalExpEarned + amount;
        this.experience = Long.MAX_VALUE - experience < amount
            ? Long.MAX_VALUE : experience + amount;
        int levels = 0;
        while (level < config.getMaxLevel()) {
            long required = config.getExpForLevel(level + 1);
            if (required <= 0 || experience < required) break;
            experience -= required;
            level++;
            levels++;
        }
        if (level >= config.getMaxLevel()) experience = 0;
        return levels;
    }

    public boolean canClaimReward(int level, TierType tier) {
        if (this.level < level) return false;
        if (tier.ordinal() > this.tier.ordinal()) return false;
        return !claimedRewards.contains(level + "-" + tier.name());
    }

    public void claimReward(int level, TierType tier) {
        claimedRewards.add(level + "-" + tier.name());
    }

    public boolean hasClaimed(int level, TierType tier) {
        return claimedRewards.contains(level + "-" + tier.name());
    }

    // Getters & Setters
    public UUID getUuid() { return uuid; }
    public String getSeasonId() { return seasonId; }
    public void setSeasonId(String seasonId) { this.seasonId = seasonId; }
    public String getPassId() { return passId; }
    public void setPassId(String passId) { this.passId = passId; }
    public TierType getTier() { return tier; }
    public void setTier(TierType tier) { this.tier = tier; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public long getExperience() { return experience; }
    public void setExperience(long experience) { this.experience = experience; }
    public long getTotalExpEarned() { return totalExpEarned; }
    public Set<String> getClaimedRewards() { return claimedRewards; }
}
