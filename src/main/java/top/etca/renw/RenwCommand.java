package top.etca.renw;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class RenwCommand implements CommandExecutor {
    private final renw plugin;

    public RenwCommand(renw plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("只有玩家可以使用此指令！");
            return true;
        }

        // 54格 GUI
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("§l每日 / 周常任务"));

        // 获取玩家任务数据
        List<QuestProgress> dailyList = plugin.getQuestManager().getDailyQuests(player);
        List<QuestProgress> weeklyList = plugin.getQuestManager().getWeeklyQuests(player);

        // -------- 第一行 ~ 第二行（索引0~17）放置每日任务（最多10个）--------
        int dailySlot = 0;
        for (QuestProgress prog : dailyList) {
            if (dailySlot >= 18) break; // 最多占两行
            Quest q = plugin.getQuestManager().getQuestById(prog.questId);
            if (q != null) {
                gui.setItem(dailySlot, createTaskItem(q, prog, "每日"));
                dailySlot++;
            }
        }

        // -------- 索引9~17可以用玻璃板填充或留空（此处跳过）--------

        // -------- 分隔行：第3行（索引18~26）全部放黑色/灰色玻璃板（分隔线）--------
        ItemStack divider = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta dividerMeta = divider.getItemMeta();
        dividerMeta.displayName(Component.text("§8========= 分隔线 ========="));
        divider.setItemMeta(dividerMeta);
        for (int i = 18; i <= 26; i++) {
            gui.setItem(i, divider.clone());
        }

        // -------- 第4~6行（索引27~53）放置周常任务（最多30个）--------
        int weeklySlot = 27;
        for (QuestProgress prog : weeklyList) {
            if (weeklySlot >= 54) break;
            Quest q = plugin.getQuestManager().getQuestById(prog.questId);
            if (q != null) {
                gui.setItem(weeklySlot, createTaskItem(q, prog, "周常"));
                weeklySlot++;
            }
        }

        player.openInventory(gui);
        return true;
    }

    /**
     * 根据任务状态创建对应的物品
     */
    private ItemStack createTaskItem(Quest q, QuestProgress prog, String type) {
        Material material;
        String statusColor;
        String statusText;

        if (prog.claimed) {
            material = Material.RED_WOOL;
            statusColor = "§c";
            statusText = "已领取";
        } else if (prog.completed) {
            material = Material.LIME_WOOL;
            statusColor = "§a";
            statusText = "可领取";
        } else {
            material = Material.PAPER;
            statusColor = "§7";
            statusText = "未完成";
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // 名称：类型颜色 + 任务名称
        String prefix = type.equals("每日") ? "§a" : "§6";
        meta.displayName(Component.text(prefix + q.name));

        // Lore
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7类型: §f" + type));
        lore.add(Component.text("§7目标: §f" + q.target + " x" + q.amount));
        lore.add(Component.text("§7进度: §e" + prog.progress + " / " + q.amount));
        lore.add(Component.text("§7状态: " + statusColor + statusText));
        if (prog.completed && !prog.claimed) {
            lore.add(Component.text("§a点击领取奖励！"));
        }
        meta.lore(lore);

        item.setItemMeta(meta);
        return item;
    }
}