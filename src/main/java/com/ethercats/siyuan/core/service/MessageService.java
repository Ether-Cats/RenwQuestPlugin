package com.ethercats.siyuan.core.service;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

public class MessageService {
    private final JavaPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String lang = "zh_CN";
    private String prefix = "§6[思源] §r";

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        messages.clear();
        lang = plugin.getConfig().getString("language", "zh_CN");
        prefix = color(plugin.getConfig().getString("prefix", "&6[思源] &r"));

        File langFile = new File(plugin.getDataFolder(), "languages/" + lang + ".yml");
        if (langFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
            for (String key : cfg.getKeys(true)) {
                if (cfg.isString(key)) messages.put(key, cfg.getString(key));
            }
        } else {
            loadDefaults();
        }
    }

    private void loadDefaults() {
        // 核心消息
        messages.put("no-permission",       "&c你没有权限执行此操作！");
        messages.put("player-only",         "&c只有玩家才能使用此命令！");
        messages.put("reload-success",      "&a插件已重载！");
        messages.put("unknown-command",     "&c未知子命令，输入 &e/{0} help &c查看帮助");
        // 通行证
        messages.put("pass.level-up",       "&6✦ &e恭喜！通行证等级提升至 &6Lv.{0}&e！");
        messages.put("pass.reward-claim",   "&a✔ &f已领取 Lv.{0} &7[{1}] &f档位的奖励");
        messages.put("pass.already-claimed","&c该奖励已领取过了");
        messages.put("pass.not-completed",  "&c你还未达到该等级");
        messages.put("pass.tier-upgraded",  "&6✦ &e恭喜！你的通行证档位升级为 &6{0}&e！");
        messages.put("pass.no-tier",        "&c你当前档位无法领取此奖励");
        // 任务
        messages.put("quest.complete",      "&a✔ &f任务完成：&e{0}");
        messages.put("quest.exp-gained",    "&7  &f获得 &e{0} &f通行证经验");
        messages.put("quest.progress",      "&7  &f进度 &e{0}/{1}");
        messages.put("quest.daily-reset",   "&6[思源] &f今日任务已重置！");
        messages.put("quest.weekly-reset",  "&6[思源] &f本周任务已重置！");
        // 商店
        messages.put("shop.list-success",   "&a成功上架 &e{0} &a个商品！");
        messages.put("shop.buy-success",    "&a购买成功！花费 &e{0}&a，获得 &e{1}x{2}");
        messages.put("shop.no-money",       "&c余额不足！需要 &e{0}，你的余额：&e{1}");
        messages.put("shop.delist-success", "&a已下架商品，物品已退回背包");
        messages.put("shop.inv-full",       "&c背包已满！请先清理背包再购买");
        messages.put("shop.own-item",       "&c不能购买自己上架的商品");
        // 传送
        messages.put("waypoint.add-success","&a传送点 &e{0} &a已保存！花费 &e{1}");
        messages.put("waypoint.del-success","&e已删除传送点，返还 &a{0}");
        messages.put("waypoint.tp-success", "&a已传送至 &e{0}");
        messages.put("waypoint.no-money",   "&c余额不足！");
        messages.put("waypoint.full",       "&c传送点已达上限（{0}/{0}）");
        // 赛季
        messages.put("season.started",      "&6[思源] &e赛季 &6{0} &e已开始！");
        messages.put("season.ended",        "&6[思源] &e赛季 &6{0} &e已结束，数据已重置。");
        messages.put("season.no-active",    "&c当前没有进行中的赛季");
    }

    public String get(String key, Object... args) {
        String raw = messages.getOrDefault(key, "§c[Missing:" + key + "]");
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                raw = raw.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }
        return color(raw);
    }

    public void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(prefix + get(key, args));
    }

    public void sendRaw(CommandSender sender, String key, Object... args) {
        sender.sendMessage(get(key, args));
    }

    public void broadcast(String key, Object... args) {
        plugin.getServer().broadcastMessage(prefix + get(key, args));
    }

    public void sendTitle(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, Object... args) {
        player.sendTitle(get(titleKey, args), get(subtitleKey, args), fadeIn, stay, fadeOut);
    }

    public void reload() {
        load();
        plugin.getLogger().info("[Message] 语言文件已重载: " + lang);
    }

    private String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}

