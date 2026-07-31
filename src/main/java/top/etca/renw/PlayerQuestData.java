package top.etca.renw;

import java.util.ArrayList;
import java.util.List;

public class PlayerQuestData {
    public List<QuestProgress> dailyQuests = new ArrayList<>();
    public List<QuestProgress> weeklyQuests = new ArrayList<>();
    public long lastDailyReset;   // 上次重置每日的时间戳（毫秒）
    public long lastWeeklyReset;  // 上次重置周常的时间戳（毫秒）

    public PlayerQuestData() {
        // 初始化时设置为0，表示从未重置过
        this.lastDailyReset = 0;
        this.lastWeeklyReset = 0;
    }
}