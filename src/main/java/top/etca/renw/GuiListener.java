package top.etca.renw;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class GuiListener implements Listener {
    private final renw plugin;

    public GuiListener(renw plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // 只处理我们自定义的 GUI
        if (!(event.getView().title() != null &&
                PlainTextComponentSerializer.plainText().serialize(event.getView().title()).contains("每日 / 周常任务"))) {
            return;
        }

        event.setCancelled(true); // 禁止移动物品

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().getType() != InventoryType.CHEST) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        // 获取物品的Lore，判断是否为任务物品
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || !meta.hasLore()) return;

        // 解析任务ID：我们需要从物品中提取任务ID，但我们的物品没有直接存储ID，可以通过名称或Lore匹配？
        // 更好的方式：将任务ID存储在物品的PersistentDataContainer或Lore中。
        // 这里为了简便，我们通过物品显示名称匹配任务名称（但可能重复），建议扩展。
        // 由于我们需要精确找到任务，修改 createTaskItem 时在Lore中加入任务ID行。
        // 但为了快速演示，我们使用插件内数据：通过玩家当前任务列表匹配名称和状态。
        // 下面采用遍历匹配方式（不严谨，但可运行）。
        // 更稳健：在创建物品时，将任务ID存入ItemMeta的PersistentDataContainer。
        // 我们在此采用临时方案：根据任务名称（唯一性假设）匹配。
        // 强烈建议后续改进为PDC存储ID。

        String displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        // 去除颜色代码，仅保留任务名（但任务名可能包含颜色，这里简化：获取物品的显示名去色）
        // 由于我们命名时用了 §a 或 §6 前缀，需要去掉。
        String cleanName = displayName.replaceAll("§[a-f0-9]", "").trim(); // 简单去除所有颜色码

        // 从QuestManager获取玩家数据，查找匹配任务
        QuestManager qm = plugin.getQuestManager();
        PlayerQuestData data = qm.getOrCreateData(player); // 注意需要将getOrCreateData设为public或提供公开方法

        // 遍历每日
        for (QuestProgress prog : data.dailyQuests) {
            Quest q = qm.getQuestById(prog.questId);
            if (q != null && q.name.equals(cleanName)) {
                // 尝试领取
                if (qm.claimReward(player, prog.questId)) {
                    player.sendMessage("§a奖励已领取！");
                    // 刷新GUI（可选）
                    player.closeInventory();
                    player.performCommand("renw"); // 重新打开
                } else {
                    player.sendMessage("§c此任务无法领取（可能未完成或已领取）");
                }
                return;
            }
        }
        for (QuestProgress prog : data.weeklyQuests) {
            Quest q = qm.getQuestById(prog.questId);
            if (q != null && q.name.equals(cleanName)) {
                if (qm.claimReward(player, prog.questId)) {
                    player.sendMessage("§a奖励已领取！");
                    player.closeInventory();
                    player.performCommand("renw");
                } else {
                    player.sendMessage("§c此任务无法领取（可能未完成或已领取）");
                }
                return;
            }
        }

        // 如果点击的是玻璃板或非任务物品，直接返回
        player.sendMessage("§e这不是可领取的任务物品。");
    }
}