package com.ethercats.siyuan.pass;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class PassConfig {
    
    public static class LevelReward {
        private final Map<TierType, List<String>> tierRewards = new EnumMap<>(TierType.class);
        
        public void addReward(TierType tier, String reward) {
            tierRewards.computeIfAbsent(tier, k -> new ArrayList<>()).add(reward);
        }
        
        public List<String> getRewards(TierType tier) {
            return tierRewards.getOrDefault(tier, List.of());
        }
    }
    
    private final String id;
    private final String name;
    private final int maxLevel;
    private final int expBase;
    private final double expMultiplier;
    private final Map<Integer, LevelReward> rewards = new HashMap<>();
    
    public PassConfig(String id, String name, int maxLevel, int expBase, double expMultiplier) {
        this.id = id;
        this.name = name;
        this.maxLevel = maxLevel;
        this.expBase = expBase;
        this.expMultiplier = expMultiplier;
    }
    
    public long getExpForLevel(int level) {
        if (level <= 1) return 0;
        return (long)(expBase * Math.pow(expMultiplier, level - 2));
    }
    
    public void addLevelReward(int level, TierType tier, String reward) {
        rewards.computeIfAbsent(level, k -> new LevelReward()).addReward(tier, reward);
    }
    
    public List<String> getRewardsForLevel(int level, TierType tier) {
        LevelReward lr = rewards.get(level);
        return lr == null ? List.of() : lr.getRewards(tier);
    }
    
    public static PassConfig load(JavaPlugin plugin, String passId) {
        File file = new File(plugin.getDataFolder(), "passes/" + passId + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("通行证配置不存在: " + passId);
            return null;
        }
        
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String id = cfg.getString("id", passId);
        String name = cfg.getString("name", "未命名通行证");
        int maxLevel = cfg.getInt("max-level", 100);
        int expBase = cfg.getInt("experience.base", 100);
        double expMultiplier = cfg.getDouble("experience.multiplier", 1.05);
        
        PassConfig pc = new PassConfig(id, name, maxLevel, expBase, expMultiplier);
        
        // 加载奖励
        if (cfg.contains("rewards")) {
            for (String levelStr : cfg.getConfigurationSection("rewards").getKeys(false)) {
                try {
                    int level = Integer.parseInt(levelStr);
                    for (String tierStr : cfg.getConfigurationSection("rewards." + levelStr).getKeys(false)) {
                        TierType tier = TierType.fromString(tierStr);
                        List<String> rewardList = cfg.getStringList("rewards." + levelStr + "." + tierStr);
                        for (String reward : rewardList) {
                            pc.addLevelReward(level, tier, reward);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        
        plugin.getLogger().info("[Pass] 已加载通行证: " + name + " (max:" + maxLevel + ")");
        return pc;
    }
    
    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public int getMaxLevel() { return maxLevel; }
}
