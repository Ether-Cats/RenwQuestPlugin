package com.ethercats.siyuan.quest;

import java.util.*;

public class PlayerQuestData {
    private final UUID uuid;
    private final String seasonId;
    private final Map<String, QuestProgress> quests = new java.util.concurrent.ConcurrentHashMap<>();
    
    public PlayerQuestData(UUID uuid, String seasonId) {
        this.uuid = uuid;
        this.seasonId = seasonId;
    }
    
    public QuestProgress getOrCreateProgress(QuestConfig config, String resetDate) {
        String key = config.getId();
        if (!quests.containsKey(key)) {
            QuestProgress prog = new QuestProgress(
                config.getId(),
                config.getType(),
                new java.util.concurrent.ConcurrentHashMap<>(),
                QuestStatus.IN_PROGRESS,
                System.currentTimeMillis(),
                null,
                resetDate
            );
            quests.put(key, prog);
        }
        return quests.get(key);
    }
    
    public QuestProgress getProgress(String questId) {
        return quests.get(questId);
    }
    
    public Map<String, QuestProgress> getAllQuests() {
        return quests;
    }
    
    public UUID getUuid() { return uuid; }
    public String getSeasonId() { return seasonId; }

    public void removeType(QuestType type) {
        quests.values().removeIf(progress -> progress.getType() == type);
    }
}
