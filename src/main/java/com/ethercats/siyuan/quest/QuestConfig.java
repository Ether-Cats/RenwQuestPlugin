package com.ethercats.siyuan.quest;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.*;

public class QuestConfig {
    
    public static class Objective {
        private final String conditionType;
        private final String target;
        private final int amount;
        
        public Objective(String conditionType, String target, int amount) {
            this.conditionType = conditionType;
            this.target = target;
            this.amount = amount;
        }
        
        public String getConditionType() { return conditionType; }
        public String getTarget() { return target; }
        public int getAmount() { return amount; }
    }
    
    private final String id;
    private final String name;
    private final String description;
    private final QuestType type;
    private final int experience;
    private final int priority;
    private final List<Objective> objectives;
    private final List<String> rewards;
    
    public QuestConfig(String id, String name, String description, QuestType type, 
                      int experience, int priority, List<Objective> objectives, List<String> rewards) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.experience = experience;
        this.priority = priority;
        this.objectives = objectives;
        this.rewards = rewards == null ? List.of() : List.copyOf(rewards);
    }

    public QuestConfig(String id, String name, String description, QuestType type,
                       int experience, int priority, List<Objective> objectives) {
        this(id, name, description, type, experience, priority, objectives, List.of());
    }
    
    public static Map<String, QuestConfig> loadAll(JavaPlugin plugin) {
        Map<String, QuestConfig> quests = new HashMap<>();
        File questDir = new File(plugin.getDataFolder(), "quests");
        if (questDir.exists()) {
          for (String typeName : new String[]{"daily", "weekly", "seasonal", "story", "challenge"}) {
            File typeDir = new File(questDir, typeName);
            if (!typeDir.exists() || !typeDir.isDirectory()) continue;
            
            File[] files = typeDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;
            
            for (File f : files) {
                try {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                    String id = cfg.getString("id", f.getName().replace(".yml", ""));
                    String name = cfg.getString("name", "未命名任务");
                    String desc = cfg.getString("description", "");
                    QuestType type = QuestType.fromString(cfg.getString("type", typeName));
                    int exp = cfg.getInt("experience", 0);
                    int priority = cfg.getInt("priority", 0);
                    List<String> rewards = new ArrayList<>();
                    rewards.addAll(cfg.getStringList("rewards"));
                    String singleReward = cfg.getString("reward");
                    if (singleReward != null && !singleReward.isBlank()) rewards.add(singleReward);
                    
                    List<Objective> objectives = new ArrayList<>();
                    if (cfg.contains("objectives")) {
                        for (Map<?, ?> objMap : cfg.getMapList("objectives")) {
                            String condType = (String) objMap.get("type");
                            String target = (String) objMap.get("target");
                            int amount = objMap.get("amount") instanceof Number ? 
                                        ((Number) objMap.get("amount")).intValue() : 1;
                            objectives.add(new Objective(condType, target, amount));
                        }
                    }
                    
                    quests.put(id, new QuestConfig(id, name, desc, type, exp, priority, objectives, rewards));
                } catch (Exception e) {
                    plugin.getLogger().warning("任务加载失败: " + f.getName() + " - " + e.getMessage());
                }
            }
          }
        }

        loadLegacyJson(plugin, quests);
        
        plugin.getLogger().info("[Quest] 已加载 " + quests.size() + " 个任务配置");
        return quests;
    }

    /** Imports the original RenwQuestPlugin items.json without requiring a manual rewrite. */
    private static void loadLegacyJson(JavaPlugin plugin, Map<String, QuestConfig> quests) {
        File file = new File(plugin.getDataFolder(), "items.json");
        if (!file.isFile()) return;
        try {
            JsonElement root = JsonParser.parseString(java.nio.file.Files.readString(file.toPath()));
            if (!root.isJsonObject()) return;
            JsonObject object = root.getAsJsonObject();
            importLegacyArray(object.getAsJsonArray("daily_quests"), QuestType.DAILY, quests);
            importLegacyArray(object.getAsJsonArray("weekly_quests"), QuestType.WEEKLY, quests);
            plugin.getLogger().info("[Quest] 已兼容导入 RenwQuestPlugin items.json");
        } catch (Exception ex) {
            plugin.getLogger().warning("[Quest] items.json 导入失败: " + ex.getMessage());
        }
    }

    private static void importLegacyArray(JsonArray array, QuestType type, Map<String, QuestConfig> quests) {
        if (array == null) return;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = string(item, "id", "legacy_" + type.name().toLowerCase() + "_" + quests.size());
            String name = string(item, "name", id);
            String condition = string(item, "condition_type", "BLOCK_BREAK");
            String target = string(item, "target", "ANY");
            int amount = Math.max(1, number(item, "amount", 1));
            int reward = Math.max(0, number(item, "reward", 0));
            List<String> rewards = reward > 0 ? List.of("money:" + reward) : List.of();
            quests.putIfAbsent(id, new QuestConfig(id, name, "RenwQuestPlugin 兼容任务", type,
                0, 100, List.of(new Objective(condition, target, amount)), rewards));
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
    
    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public QuestType getType() { return type; }
    public int getExperience() { return experience; }
    public int getPriority() { return priority; }
    public List<Objective> getObjectives() { return objectives; }
    public List<String> getRewards() { return rewards; }
}
