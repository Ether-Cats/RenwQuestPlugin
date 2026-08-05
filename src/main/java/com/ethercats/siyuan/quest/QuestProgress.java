package com.ethercats.siyuan.quest;

import java.util.*;

public class QuestProgress {
    private final String questId;
    private final QuestType type;
    private final Map<Integer, Integer> objectiveProgress; // 目标索引 -> 当前进度
    private QuestStatus status;
    private final long startedAt;
    private Long completedAt;
    private final String resetDate; // YYYY-MM-DD 格式
    
    public QuestProgress(String questId, QuestType type, Map<Integer, Integer> objectiveProgress,
                        QuestStatus status, long startedAt, Long completedAt, String resetDate) {
        this.questId = questId;
        this.type = type;
        this.objectiveProgress = objectiveProgress != null ? new java.util.concurrent.ConcurrentHashMap<>(objectiveProgress) : new java.util.concurrent.ConcurrentHashMap<>();
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.resetDate = resetDate;
    }
    
    public void addProgress(int objIndex, int amount) {
        if (amount <= 0) return;
        objectiveProgress.merge(objIndex, amount, (oldValue, delta) -> {
            long next = (long) oldValue + delta;
            return (int) Math.min(Integer.MAX_VALUE, next);
        });
    }
    
    public int getProgress(int objIndex) {
        return objectiveProgress.getOrDefault(objIndex, 0);
    }
    
    public boolean isCompleted(QuestConfig config) {
        for (int i = 0; i < config.getObjectives().size(); i++) {
            if (getProgress(i) < config.getObjectives().get(i).getAmount()) {
                return false;
            }
        }
        return true;
    }
    
    public void markCompleted() {
        this.status = QuestStatus.COMPLETED;
        this.completedAt = System.currentTimeMillis();
    }
    
    public void markClaimed() {
        this.status = QuestStatus.CLAIMED;
    }
    
    // Getters
    public String getQuestId() { return questId; }
    public QuestType getType() { return type; }
    public Map<Integer, Integer> getObjectiveProgress() { return objectiveProgress; }
    public QuestStatus getStatus() { return status; }
    public void setStatus(QuestStatus status) { this.status = status; }
    public long getStartedAt() { return startedAt; }
    public Long getCompletedAt() { return completedAt; }
    public String getResetDate() { return resetDate; }
}
