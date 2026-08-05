package com.ethercats.siyuan.pass.reward;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.logging.Level;

public class RewardExecutor {
    
    public static void executeReward(Player player, String rewardStr, SiYuanPlugin plugin) {
        if (rewardStr == null || rewardStr.isEmpty()) return;
        
        String[] parts = rewardStr.split(":", 2);
        if (parts.length < 2) return;
        
        String type = parts[0].toLowerCase();
        String value = parts[1];
        
        try {
            switch (type) {
                case "money" -> {
                    double amount = Double.parseDouble(value);
                    // 通胀控制：乘以全局倍率
                    double multiplier = plugin.getConfig().getDouble("pass.reward-money-multiplier", 0.85);
                    double actual = amount * multiplier;
                    double granted = plugin.getEconomyService().mintReward(player, actual, "PASS_REWARD");
                    if (granted > 0) player.sendMessage("§a+ §e" + plugin.getEconomyService().format(granted));
                    else player.sendMessage("§7本日奖励货币额度已达到上限");
                }
                case "exp" -> {
                    long exp = Long.parseLong(value);
                    plugin.getPassManager().addExperience(player, exp);
                }
                case "item" -> {
                    String[] itemParts = value.split(":");
                    Material mat = Material.valueOf(itemParts[0].toUpperCase());
                    int amount = itemParts.length > 1 ? Integer.parseInt(itemParts[1]) : 1;
                    String displayName = itemParts.length > 2 ? itemParts[2].replace("_", " ") : null;
                    int remaining = Math.max(1, amount);
                    while (remaining > 0) {
                        ItemStack item = new ItemStack(mat, Math.min(remaining, mat.getMaxStackSize()));
                        if (displayName != null) {
                            ItemMeta meta = item.getItemMeta();
                            meta.setDisplayName(displayName);
                            item.setItemMeta(meta);
                        }
                        player.getInventory().addItem(item).values().forEach(leftover ->
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                        remaining -= item.getAmount();
                    }
                    player.sendMessage("§a+ §f" + amount + "x " + mat.name());
                }
                case "command" -> {
                    String cmd = value.replace("{player}", player.getName())
                                      .replace("{uuid}", player.getUniqueId().toString());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
                case "permission" -> {
                    String[] permParts = value.split(":");
                    String perm = permParts[0].replaceAll("[^A-Za-z0-9_.-]", "");
                    int days = permParts.length > 1 ? Integer.parseInt(permParts[1]) : 30;
                    if (plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null && !perm.isEmpty()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " permission settemp " + perm + " true " + Math.max(1, days) + "d");
                        player.sendMessage("§a+ §6权限: §f" + perm + " §7(" + days + "天)");
                    } else {
                        player.sendMessage("§7奖励需要 LuckPerms 才能生效: §f" + perm);
                    }
                }
                case "title" -> {
                    String command = plugin.getConfig().getString("integrations.title-command", "");
                    if (command != null && !command.isBlank()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command
                            .replace("{player}", player.getName()).replace("{title}", value));
                    }
                    player.sendMessage("§a+ §6称号: §f" + value);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "奖励执行失败: " + rewardStr, e);
        }
    }
}
