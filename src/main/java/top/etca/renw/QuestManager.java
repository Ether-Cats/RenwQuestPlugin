package top.etca.renw;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class QuestManager {
    private final renw plugin;
    private final Gson gson = new Gson();

    // 全局任务池
    public List<Quest> dailyPool = new ArrayList<>();
    public List<Quest> weeklyPool = new ArrayList<>();

    // 玩家数据存储 (内存)
    private final Map<UUID, PlayerQuestData> playerDataMap = new ConcurrentHashMap<>();

    public QuestManager(renw plugin) {
        this.plugin = plugin;
    }

    // ---------- 加载任务池 ----------
    public void loadQuests() {
        File file = new File(plugin.getDataFolder(), "items.json");
        if (!file.exists()) {
            plugin.saveResource("items.json", false);
            file = new File(plugin.getDataFolder(), "items.json");
            if (!file.exists()) {
                plugin.getLogger().severe("无法创建 items.json 文件！");
                return;
            }
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);

            JsonArray dailyArray = json.getAsJsonArray("daily_quests");
            if (dailyArray != null) {
                dailyPool.clear();
                for (var element : dailyArray) {
                    Quest q = gson.fromJson(element, Quest.class);
                    dailyPool.add(q);
                }
                plugin.getLogger().info("已加载 " + dailyPool.size() + " 个每日任务");
            }

            JsonArray weeklyArray = json.getAsJsonArray("weekly_quests");
            if (weeklyArray != null) {
                weeklyPool.clear();
                for (var element : weeklyArray) {
                    Quest q = gson.fromJson(element, Quest.class);
                    weeklyPool.add(q);
                }
                plugin.getLogger().info("已加载 " + weeklyPool.size() + " 个周常任务");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("加载任务列表失败！");
            e.printStackTrace();
        }
    }

    // ---------- 获取玩家数据，不存在则初始化 ----------
    // 关键修改：将 private 改为 public
    public PlayerQuestData getOrCreateData(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerQuestData data = playerDataMap.get(uuid);
        if (data == null) {
            data = new PlayerQuestData();
            playerDataMap.put(uuid, data);
            regenerateDaily(player, data);
            regenerateWeekly(player, data);
        } else {
            long now = System.currentTimeMillis();
            if (now - data.lastDailyReset >= 24 * 60 * 60 * 1000) {
                regenerateDaily(player, data);
            }
            if (now - data.lastWeeklyReset >= 7 * 24 * 60 * 60 * 1000) {
                regenerateWeekly(player, data);
            }
        }
        return data;
    }

    private void regenerateDaily(Player player, PlayerQuestData data) {
        List<Quest> shuffled = new ArrayList<>(dailyPool);
        Collections.shuffle(shuffled);
        int count = Math.min(10, shuffled.size());
        data.dailyQuests.clear();
        for (int i = 0; i < count; i++) {
            Quest q = shuffled.get(i);
            QuestProgress prog = new QuestProgress(q.id);
            data.dailyQuests.add(prog);
        }
        data.lastDailyReset = System.currentTimeMillis();
    }

    private void regenerateWeekly(Player player, PlayerQuestData data) {
        List<Quest> shuffled = new ArrayList<>(weeklyPool);
        Collections.shuffle(shuffled);
        int count = Math.min(30, shuffled.size());
        data.weeklyQuests.clear();
        for (int i = 0; i < count; i++) {
            Quest q = shuffled.get(i);
            QuestProgress prog = new QuestProgress(q.id);
            data.weeklyQuests.add(prog);
        }
        data.lastWeeklyReset = System.currentTimeMillis();
    }

    // ---------- 获取玩家当前任务列表（供GUI使用） ----------
    public List<QuestProgress> getDailyQuests(Player player) {
        PlayerQuestData data = getOrCreateData(player);
        return data.dailyQuests;
    }

    public List<QuestProgress> getWeeklyQuests(Player player) {
        PlayerQuestData data = getOrCreateData(player);
        return data.weeklyQuests;
    }

    // ---------- 根据任务ID获取任务定义 ----------
    public Quest getQuestById(String id) {
        for (Quest q : dailyPool) {
            if (q.id.equals(id)) return q;
        }
        for (Quest q : weeklyPool) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }

    // ---------- 添加进度 ----------
    public void addProgress(Player player, String conditionType, String target, int amount) {
        PlayerQuestData data = getOrCreateData(player);
        boolean anyUpdated = false;

        for (QuestProgress prog : data.dailyQuests) {
            if (prog.completed || prog.claimed) continue;
            Quest q = getQuestById(prog.questId);
            if (q == null) continue;
            if (q.condition_type.equals(conditionType) && q.target.equals(target)) {
                prog.progress += amount;
                if (prog.progress >= q.amount) {
                    prog.progress = q.amount;
                    prog.completed = true;
                    player.sendMessage("§a[任务] §f" + q.name + " §a已完成！请打开菜单领取奖励。");
                }
                anyUpdated = true;
            }
        }

        for (QuestProgress prog : data.weeklyQuests) {
            if (prog.completed || prog.claimed) continue;
            Quest q = getQuestById(prog.questId);
            if (q == null) continue;
            if (q.condition_type.equals(conditionType) && q.target.equals(target)) {
                prog.progress += amount;
                if (prog.progress >= q.amount) {
                    prog.progress = q.amount;
                    prog.completed = true;
                    player.sendMessage("§a[任务] §f" + q.name + " §a已完成！请打开菜单领取奖励。");
                }
                anyUpdated = true;
            }
        }
    }

    // ---------- 领取奖励 ----------
    public boolean claimReward(Player player, String questId) {
        PlayerQuestData data = getOrCreateData(player);
        QuestProgress prog = findProgress(data, questId);
        if (prog == null) return false;
        if (!prog.completed || prog.claimed) return false;

        Quest q = getQuestById(questId);
        if (q == null) return false;

        // 发放奖励（示例：经验）
        player.sendMessage("§e你已领取任务 §f" + q.name + " §e的奖励！");
        player.giveExp(q.reward);  // 可替换为Vault经济

        prog.claimed = true;
        return true;
    }

    private QuestProgress findProgress(PlayerQuestData data, String questId) {
        for (QuestProgress p : data.dailyQuests) {
            if (p.questId.equals(questId)) return p;
        }
        for (QuestProgress p : data.weeklyQuests) {
            if (p.questId.equals(questId)) return p;
        }
        return null;
    }

    // ---------- 数据持久化（暂未实现） ----------
    public void savePlayerData() {
        // 后续可将 playerDataMap 保存为 JSON 文件
    }
}