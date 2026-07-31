package top.etca.renw;

public class QuestProgress {
    public String questId;
    public int progress;
    public boolean completed;   // 任务是否已完成（可领取）
    public boolean claimed;     // 奖励是否已领取

    public QuestProgress(String questId) {
        this.questId = questId;
        this.progress = 0;
        this.completed = false;
        this.claimed = false;
    }
}