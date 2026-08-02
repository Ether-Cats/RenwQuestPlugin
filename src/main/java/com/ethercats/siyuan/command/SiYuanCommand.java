package com.ethercats.siyuan.command;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.pass.TierType;
import com.ethercats.siyuan.quest.QuestType;
import com.ethercats.siyuan.season.SeasonManager;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class SiYuanCommand implements CommandExecutor, TabCompleter {

    private final SiYuanPlugin plugin;

    public SiYuanCommand(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 所有功能统一从 /gc 进入。
        if (args.length == 0) {
            if (sender instanceof Player p) plugin.getGuiManager().openMain(p);
            else sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {

            case "reload" -> {
                if (!sender.hasPermission("siyuan.admin")) {
                    plugin.getMessageService().send(sender, "no-permission"); return true;
                }
                plugin.reload();
                plugin.getMessageService().send(sender, "reload-success");
            }

            case "pass" -> {
                String operation = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "open";
                if (operation.equals("addexp") || operation.equals("setlevel")) {
                    if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return true; }
                    if (args.length < 4) {
                        sender.sendMessage("§c用法: /gc pass " + operation + " <玩家> <数量>");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[2]);
                    if (target == null) { sender.sendMessage("§c玩家不在线"); return true; }
                    try {
                        if (operation.equals("addexp")) {
                            long exp = Long.parseLong(args[3]);
                            if (exp <= 0) { sender.sendMessage("§c经验必须大于 0"); return true; }
                            plugin.getPassManager().addExperience(target, exp);
                            sender.sendMessage("§a已给予 " + target.getName() + " " + exp + " 通行证经验");
                        } else {
                            int level = Integer.parseInt(args[3]);
                            if (level < 1) { sender.sendMessage("§c等级不能小于 1"); return true; }
                            plugin.getPassManager().setLevel(target, level);
                            sender.sendMessage("§a已设置 " + target.getName() + " 等级为 " + level);
                        }
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§c数量格式错误");
                    }
                    return true;
                }
                if (!(sender instanceof Player p)) { plugin.getMessageService().send(sender, "player-only"); return true; }
                if (operation.equals("upgrade")) {
                    if (args.length < 3 || (!args[2].equalsIgnoreCase("premium") && !args[2].equalsIgnoreCase("vip"))) {
                        sender.sendMessage("§c用法: /gc pass upgrade <premium|vip>");
                        return true;
                    }
                    plugin.getPassManager().upgradeTier(p, TierType.fromString(args[2]));
                } else {
                    plugin.getPassManager().openGUI(p);
                }
            }

            case "quest" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return true; }
                    plugin.getQuestManager().runDailyReset();
                    sender.sendMessage("§a日常任务已重置");
                    return true;
                }
                if (!(sender instanceof Player p)) { plugin.getMessageService().send(sender, "player-only"); return true; }
                if (args.length >= 2) {
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "daily"    -> plugin.getQuestManager().openGUI(p, QuestType.DAILY);
                        case "weekly"   -> plugin.getQuestManager().openGUI(p, QuestType.WEEKLY);
                        case "seasonal" -> plugin.getQuestManager().openGUI(p, QuestType.SEASONAL);
                        case "story"    -> plugin.getQuestManager().openGUI(p, QuestType.STORY);
                        case "challenge"-> plugin.getQuestManager().openGUI(p, QuestType.CHALLENGE);
                        default -> plugin.getQuestManager().openGUI(p, QuestType.DAILY);
                    }
                } else {
                    plugin.getQuestManager().openGUI(p, QuestType.DAILY);
                }
            }

            case "season" -> {
                if (!sender.hasPermission("siyuan.admin")) {
                    plugin.getMessageService().send(sender, "no-permission"); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§6赛季管理：/gc season <start|end|info|leaderboard>");
                    return true;
                }
                SeasonManager sm = plugin.getSeasonManager();
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "start" -> {
                        String seasonName = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "新赛季";
                        sm.startSeason(seasonName);
                        sender.sendMessage("§a赛季已开启：" + seasonName);
                    }
                    case "end" -> {
                        if (sm.getActiveSeason() == null) {
                            sender.sendMessage("§c当前没有进行中的赛季");
                        } else {
                            sm.endSeason();
                            sender.sendMessage("§a赛季已结束，数据已重置");
                        }
                    }
                    case "info" -> {
                        if (sm.getActiveSeason() == null) {
                            sender.sendMessage("§7当前没有进行中的赛季");
                        } else {
                            sender.sendMessage("§6当前赛季: §f" + sm.getActiveSeason().getName());
                            sender.sendMessage("§7开始时间: §f" + new java.util.Date(sm.getActiveSeason().getStartTime()));
                        }
                    }
                    case "leaderboard", "top" -> {
                        int topN = 10;
                        if (args.length >= 3) {
                            try { topN = Math.max(1, Math.min(100, Integer.parseInt(args[2]))); }
                            catch (NumberFormatException ex) { sender.sendMessage("§c排行数量格式错误"); return true; }
                        }
                        sm.getLeaderboard(topN).forEach(entry ->
                            sender.sendMessage("§e" + entry));
                    }
                }
            }

            case "shop" -> {
                if (!(sender instanceof Player p)) { plugin.getMessageService().send(sender, "player-only"); return true; }
                handleShop(p, sender, args, 1);
            }

            case "wp" -> {
                if (!(sender instanceof Player p)) { plugin.getMessageService().send(sender, "player-only"); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("add")) {
                    String waypointName = args.length >= 3 ? args[2] : "传送点";
                    String icon = args.length >= 4 ? args[3] : "RECOVERY_COMPASS";
                    plugin.getWaypointManager().addWaypoint(p, p.getLocation(), icon, waypointName);
                } else {
                    plugin.getWaypointManager().openGUI(p);
                }
            }

            case "menu" -> handleMenu(sender, Arrays.copyOfRange(args, 1, args.length));

            case "help" -> sendHelp(sender, label);

            default -> plugin.getMessageService().send(sender, "unknown-command", label);
        }
        return true;
    }

    private void handleMenu(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§6/gc menu open <菜单名> §7打开菜单");
            sender.sendMessage("§6/gc menu edit <菜单名> [行数] [标题] §7游戏内编辑菜单");
            sender.sendMessage("§6/gc menu save|cancel §7保存或放弃编辑");
            sender.sendMessage("§6/gc menu action <槽位> <left|right|all> <动作|clear> §7设置点击动作");
            sender.sendMessage("§6/gc menu title|permission <值> §7设置标题或打开权限");
            sender.sendMessage("§6/gc menu list §7列出菜单");
            sender.sendMessage("§6/gc menu reload §7重载菜单");
            sender.sendMessage("§6/gc menu sync §7立即拉取 Web 已发布菜单");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> {
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                if (args.length < 2) { sender.sendMessage("§c用法: /gc menu open <菜单名>"); return; }
                plugin.getDynamicMenuManager().open(player, args[1]);
            }
            case "edit" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                if (args.length < 2) { sender.sendMessage("§c用法: /gc menu edit <菜单名> [行数] [标题]"); return; }
                if (args.length < 3) {
                    plugin.getMenuEditorManager().openEditor(player, args[1]);
                    return;
                }
                try {
                    int rows = Integer.parseInt(args[2]);
                    String title = args.length >= 4
                        ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : Optional.ofNullable(plugin.getDynamicMenuManager().getEditableMenu(args[1]))
                            .map(menu -> menu.title())
                            .orElse(args[1]);
                    plugin.getMenuEditorManager().openEditor(player, args[1], rows, title);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c行数必须是 1 到 6 的整数");
                }
            }
            case "save" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                plugin.getMenuEditorManager().saveOpenEditor(player);
            }
            case "cancel" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                plugin.getMenuEditorManager().cancelOpenEditor(player);
            }
            case "action" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /gc menu action <槽位> <left|right|all> <动作|clear>");
                    return;
                }
                try {
                    int slot = Integer.parseInt(args[1]);
                    String clickType = args[2].toLowerCase(Locale.ROOT);
                    if (!clickType.equals("left") && !clickType.equals("right") && !clickType.equals("all")) {
                        sender.sendMessage("§c点击类型只能是 left、right 或 all");
                        return;
                    }
                    String action = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                    plugin.getMenuEditorManager().setItemAction(player, slot, clickType, action);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c槽位必须是整数");
                }
            }
            case "title" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                if (args.length < 2) { sender.sendMessage("§c用法: /gc menu title <标题>"); return; }
                plugin.getMenuEditorManager().setTitle(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "permission" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                if (!(sender instanceof Player player)) { plugin.getMessageService().send(sender, "player-only"); return; }
                if (args.length < 2) { sender.sendMessage("§c用法: /gc menu permission <权限节点|none>"); return; }
                plugin.getMenuEditorManager().setPermission(player, args[1]);
            }
            case "list" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                List<String> names = plugin.getDynamicMenuManager().getMenuNames();
                sender.sendMessage(names.isEmpty() ? "§7暂无菜单" : "§a已加载菜单: §f" + String.join(", ", names));
            }
            case "reload" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                plugin.getDynamicMenuManager().reload();
                sender.sendMessage("§a菜单已重载");
            }
            case "sync" -> {
                if (!sender.hasPermission("siyuan.admin")) { plugin.getMessageService().send(sender, "no-permission"); return; }
                plugin.getRemoteMenuSyncService().requestSync(sender);
            }
            default -> sender.sendMessage("§c未知操作，使用 /gc menu help");
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6========= §eSiYuan 思源 §6=========");
        sender.sendMessage("§e/" + label + " reload §7- 重载配置");
        sender.sendMessage("§e/" + label + " pass §7- 通行证界面");
        sender.sendMessage("§e/" + label + " quest [daily|weekly|seasonal|story|challenge] §7- 任务界面");
        sender.sendMessage("§e/" + label + " season <start|end|info> §7- 赛季管理");
        sender.sendMessage("§e/" + label + " shop [list|buy|delist] §7- 全球商店");
        sender.sendMessage("§e/" + label + " wp §7- 传送点管理");
        sender.sendMessage("§e/" + label + " menu open <名称> §7- 打开自定义菜单");
        if (sender.hasPermission("siyuan.admin")) {
            sender.sendMessage("§e/" + label + " menu edit <名称> [行数] [标题] §7- 游戏内编辑菜单");
        }
    }

    private void handleShop(Player player, CommandSender sender, String[] args, int offset) {
        if (args.length <= offset) {
            plugin.getShopManager().openGUI(player);
            return;
        }
        switch (args[offset].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length <= offset + 1) {
                    sender.sendMessage("§c用法: /gc shop list <单价> [数量]");
                    return;
                }
                try {
                    double price = Double.parseDouble(args[offset + 1]);
                    int amount = args.length > offset + 2
                        ? Integer.parseInt(args[offset + 2]) : player.getInventory().getItemInMainHand().getAmount();
                    plugin.getShopManager().listItem(player, player.getInventory().getItemInMainHand(), amount, price);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c价格或数量格式错误");
                }
            }
            case "buy" -> {
                if (args.length <= offset + 1) {
                    sender.sendMessage("§c用法: /gc shop buy <商品ID> [数量]");
                    return;
                }
                try {
                    int amount = args.length > offset + 2 ? Integer.parseInt(args[offset + 2]) : 1;
                    plugin.getShopManager().buyItem(player, args[offset + 1], amount);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c数量格式错误");
                }
            }
            case "delist" -> {
                if (args.length <= offset + 1) {
                    sender.sendMessage("§c用法: /gc shop delist <商品ID>");
                    return;
                }
                plugin.getShopManager().delistItem(player, args[offset + 1]);
            }
            default -> plugin.getShopManager().openGUI(player);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(List.of("pass", "quest", "shop", "wp", "menu", "help"));
            if (sender.hasPermission("siyuan.admin")) {
                completions.addAll(List.of("season", "reload"));
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "pass" -> {
                    completions.add("upgrade");
                    if (sender.hasPermission("siyuan.admin")) completions.addAll(List.of("addexp", "setlevel"));
                }
                case "quest" -> {
                    completions.addAll(List.of("daily", "weekly", "seasonal", "story", "challenge"));
                    if (sender.hasPermission("siyuan.admin")) completions.add("reset");
                }
                case "season" -> {
                    if (sender.hasPermission("siyuan.admin")) completions.addAll(List.of("start", "end", "info", "leaderboard"));
                }
                case "shop" -> completions.addAll(List.of("list", "buy", "delist"));
                case "wp" -> completions.add("add");
                case "menu" -> {
                    completions.add("open");
                    if (sender.hasPermission("siyuan.admin")) {
                        completions.addAll(List.of("edit", "save", "cancel", "action", "title", "permission", "list", "reload", "sync"));
                    }
                }
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("pass") && args[1].equalsIgnoreCase("upgrade")) {
                completions.addAll(List.of("premium", "vip"));
            } else if (args[0].equalsIgnoreCase("pass")
                && sender.hasPermission("siyuan.admin")
                && (args[1].equalsIgnoreCase("addexp") || args[1].equalsIgnoreCase("setlevel"))) {
                Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
            } else if (args[0].equalsIgnoreCase("menu")
                && (args[1].equalsIgnoreCase("open") || args[1].equalsIgnoreCase("edit"))) {
                completions.addAll(plugin.getDynamicMenuManager().getMenuNames());
            } else if (args[0].equalsIgnoreCase("menu") && args[1].equalsIgnoreCase("permission")) {
                completions.add("none");
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("menu") && args[1].equalsIgnoreCase("action")) {
            completions.addAll(List.of("left", "right", "all"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("menu") && args[1].equalsIgnoreCase("action")) {
            completions.addAll(List.of("command:", "console:", "tell:", "menu:", "sound:", "close", "clear"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("wp") && args[1].equalsIgnoreCase("add")) {
            completions.addAll(List.of("RECOVERY_COMPASS", "RED_BED", "EMERALD", "LODESTONE", "NETHER_STAR"));
        }

        String partial = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        completions.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(partial));
        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }
}
