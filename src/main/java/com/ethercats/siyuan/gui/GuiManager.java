package com.ethercats.siyuan.gui;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.pass.PassConfig;
import com.ethercats.siyuan.pass.PlayerPassData;
import com.ethercats.siyuan.pass.TierType;
import com.ethercats.siyuan.quest.PlayerQuestData;
import com.ethercats.siyuan.quest.QuestConfig;
import com.ethercats.siyuan.quest.QuestProgress;
import com.ethercats.siyuan.quest.QuestStatus;
import com.ethercats.siyuan.quest.QuestType;
import com.ethercats.siyuan.shop.ShopItem;
import com.ethercats.siyuan.shop.ShopTransaction;
import com.ethercats.siyuan.waypoint.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager implements Listener {
    private static final int PAGE_SIZE = 36;

    private final SiYuanPlugin plugin;
    private final Map<UUID, ListingSession> listingSessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> waypointIcons = new ConcurrentHashMap<>();
    private static final List<Material> WAYPOINT_ICONS = List.of(
        Material.RECOVERY_COMPASS, Material.RED_BED, Material.EMERALD,
        Material.LODESTONE, Material.NETHER_STAR
    );

    public GuiManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        MenuHolder holder = new MenuHolder("main", 0, null, 0);
        Inventory inv = create(holder, 45, "&6&lsiyuan &8| &f思渊功能中心");
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(10, item(Material.GOLDEN_CHESTPLATE, "&6赛季通行证", List.of("&7查看等级、档位和奖励", "", "&e点击打开")));
        inv.setItem(12, item(Material.WRITABLE_BOOK, "&e任务中心", List.of("&7每日、每周和赛季任务", "", "&e点击打开")));
        inv.setItem(14, item(Material.EMERALD, "&a全球商店", List.of("&7玩家之间安全交易", "", "&e点击打开")));
        inv.setItem(16, item(Material.RECOVERY_COMPASS, "&b传送点", List.of("&7保存和管理个人位置", "", "&e点击打开")));
        var season = plugin.getSeasonManager().getActiveSeason();
        inv.setItem(31, item(Material.CLOCK, "&d当前赛季", List.of(
            season == null ? "&7暂无进行中的赛季" : "&f" + season.getName(),
            season == null ? "&8管理员可使用 /gc season start" : "&7已运行 " + formatDuration(System.currentTimeMillis() - season.getStartTime())
        )));
        inv.setItem(40, item(Material.BARRIER, "&c关闭", List.of()));
        player.openInventory(inv);
    }

    public void openPass(Player player) {
        openPass(player, 0);
    }

    public void openPass(Player player, int requestedPage) {
        PassConfig config = plugin.getPassManager().getPassConfig();
        if (config == null) {
            player.sendMessage("§c通行证配置加载失败，请联系管理员");
            return;
        }
        int maxPage = Math.max(0, (config.getMaxLevel() - 1) / PAGE_SIZE);
        int page = clamp(requestedPage, 0, maxPage);
        PlayerPassData data = plugin.getPassManager().getPlayerData(player.getUniqueId());
        MenuHolder holder = new MenuHolder("pass", page, null, 0);
        Inventory inv = create(holder, 54, "&6通行证 &8| &f" + config.getName());

        inv.setItem(4, item(Material.NETHER_STAR, "&6你的通行证", List.of(
            "&7档位: " + tierName(data.getTier()),
            "&7等级: &e" + data.getLevel() + "&7/&e" + config.getMaxLevel(),
            data.getLevel() >= config.getMaxLevel()
                ? "&a已达到最高等级"
                : "&7经验: &e" + data.getExperience() + "&7/&e" + config.getExpForLevel(data.getLevel() + 1)
        )));

        int firstLevel = page * PAGE_SIZE + 1;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int level = firstLevel + i;
            if (level > config.getMaxLevel()) break;
            boolean unlocked = data.getLevel() >= level;
            boolean hasRewards = hasRewards(config, level);
            Material icon = !hasRewards ? Material.GRAY_DYE : unlocked ? Material.CHEST : Material.MINECART;
            List<String> lore = new ArrayList<>();
            lore.add(unlocked ? "&a已解锁" : "&7需要达到 Lv." + level);
            appendRewardState(lore, config, data, level, TierType.FREE);
            appendRewardState(lore, config, data, level, TierType.PREMIUM);
            appendRewardState(lore, config, data, level, TierType.VIP);
            if (hasRewards) lore.add("&e点击查看奖励");
            inv.setItem(9 + i, item(icon, "&e等级 " + level, lore));
            if (hasRewards) holder.actions.put(9 + i, String.valueOf(level));
        }

        navigation(inv, page, maxPage);
        inv.setItem(49, item(Material.ARROW, "&f返回功能中心", List.of()));
        inv.setItem(50, item(Material.GOLD_INGOT, "&6升级高级档", List.of("&7价格: &e" + money("pass.tier-prices.premium"), "&e点击购买")));
        inv.setItem(51, item(Material.NETHERITE_INGOT, "&d升级至尊档", List.of("&7价格: &e" + money("pass.tier-prices.vip"), "&e点击购买")));
        player.openInventory(inv);
    }

    private void openPassReward(Player player, int level) {
        PassConfig config = plugin.getPassManager().getPassConfig();
        PlayerPassData data = plugin.getPassManager().getPlayerData(player.getUniqueId());
        MenuHolder holder = new MenuHolder("pass_reward", 0, null, level);
        Inventory inv = create(holder, 27, "&6通行证奖励 &8| &fLv." + level);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        putTierReward(inv, 11, config, data, level, TierType.FREE, Material.CHEST);
        putTierReward(inv, 13, config, data, level, TierType.PREMIUM, Material.ENDER_CHEST);
        putTierReward(inv, 15, config, data, level, TierType.VIP, Material.NETHERITE_BLOCK);
        inv.setItem(22, item(Material.ARROW, "&f返回", List.of()));
        player.openInventory(inv);
    }

    public void openQuests(Player player, QuestType type) {
        openQuests(player, type, 0);
    }

    public void openQuests(Player player, QuestType type, int requestedPage) {
        List<QuestConfig> quests = plugin.getQuestManager().getAssignedQuests(player.getUniqueId(), type);
        int maxPage = Math.max(0, (quests.size() - 1) / PAGE_SIZE);
        int page = clamp(requestedPage, 0, maxPage);
        MenuHolder holder = new MenuHolder("quest", page, type.name(), 0);
        Inventory inv = create(holder, 54, "&e任务中心 &8| &f" + questTypeName(type));
        PlayerQuestData data = plugin.getQuestManager().getPlayerData(player.getUniqueId());

        inv.setItem(0, typeButton(QuestType.DAILY, type));
        inv.setItem(1, typeButton(QuestType.WEEKLY, type));
        inv.setItem(2, typeButton(QuestType.SEASONAL, type));
        inv.setItem(3, typeButton(QuestType.STORY, type));
        inv.setItem(4, typeButton(QuestType.CHALLENGE, type));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < quests.size(); i++) {
            QuestConfig quest = quests.get(start + i);
            QuestProgress progress = data.getProgress(quest.getId());
            QuestStatus status = progress == null ? QuestStatus.IN_PROGRESS : progress.getStatus();
            Material icon = status == QuestStatus.COMPLETED || status == QuestStatus.CLAIMED
                ? Material.LIME_DYE : Material.PAPER;
            List<String> lore = new ArrayList<>();
            lore.add("&7" + quest.getDescription());
            lore.add("");
            for (int objective = 0; objective < quest.getObjectives().size(); objective++) {
                QuestConfig.Objective obj = quest.getObjectives().get(objective);
                int current = progress == null ? 0 : Math.min(progress.getProgress(objective), obj.getAmount());
                lore.add("&f" + objectiveName(obj) + " &e" + current + "&7/&e" + obj.getAmount());
            }
            lore.add("");
            lore.add(status == QuestStatus.CLAIMED ? "&a奖励已领取" : status == QuestStatus.COMPLETED ? "&e点击领取奖励" : "&7奖励: &e" + quest.getExperience() + " 通行证经验");
            inv.setItem(9 + i, item(icon, quest.getName(), lore));
            holder.actions.put(9 + i, quest.getId());
        }
        navigation(inv, page, maxPage);
        inv.setItem(49, item(Material.ARROW, "&f返回功能中心", List.of()));
        player.openInventory(inv);
    }

    public void openShop(Player player) {
        openShop(player, 0);
    }

    public void openShop(Player player, int requestedPage) {
        List<ShopItem> items = plugin.getShopManager().getListings().values().stream()
            .sorted(Comparator.comparingLong(ShopItem::getListedAt).reversed())
            .toList();
        int maxPage = Math.max(0, (items.size() - 1) / 45);
        int page = clamp(requestedPage, 0, maxPage);
        MenuHolder holder = new MenuHolder("shop", page, null, 0);
        Inventory inv = create(holder, 54, "&a全球商店 &8| &f第 " + (page + 1) + " 页");
        int start = page * 45;
        for (int i = 0; i < 45 && start + i < items.size(); i++) {
            ShopItem listing = items.get(start + i);
            ItemStack display = listing.toItemStack();
            if (display == null) display = new ItemStack(Material.BARRIER);
            display.setAmount(Math.max(1, Math.min(display.getMaxStackSize(), listing.getAmount())));
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(color("&7卖家: &f" + listing.getSellerName()));
            lore.add(color("&7库存: &e" + listing.getAmount()));
            lore.add(color("&7单价: &e" + plugin.getEconomyService().format(listing.getPricePerUnit())));
            lore.add(color("&7总价: &6" + plugin.getEconomyService().format(listing.getTotalPrice())));
            lore.add(color(player.getUniqueId().equals(listing.getSellerUuid()) ? "&e点击管理商品" : "&a点击选择购买数量"));
            meta.setLore(lore);
            display.setItemMeta(meta);
            inv.setItem(i, display);
            holder.actions.put(i, listing.getId());
        }
        navigation(inv, page, maxPage);
        inv.setItem(47, item(Material.CHEST, "&e我的商品", List.of("&7查看并下架自己的商品", "&e点击打开")));
        inv.setItem(48, item(Material.WRITABLE_BOOK, "&b交易记录", List.of("&7查看近 7 日买卖记录", "&e点击打开")));
        inv.setItem(49, item(Material.ANVIL, "&a上架手持物品", List.of("&7使用菜单设置数量与单价", "&e点击开始")));
        inv.setItem(50, item(Material.ARROW, "&f返回功能中心", List.of()));
        player.openInventory(inv);
    }

    private void openMyShop(Player player) {
        List<ShopItem> items = plugin.getShopManager().getListingsForSeller(player.getUniqueId());
        MenuHolder holder = new MenuHolder("shop_mine", 0, null, 0);
        Inventory inv = create(holder, 54, "&e我的商品");
        for (int i = 0; i < Math.min(45, items.size()); i++) {
            ShopItem listing = items.get(i);
            ItemStack display = listing.toItemStack();
            if (display == null) display = new ItemStack(Material.BARRIER);
            display.setAmount(Math.max(1, Math.min(display.getMaxStackSize(), listing.getAmount())));
            ItemMeta meta = display.getItemMeta();
            meta.setLore(List.of(color("&7库存: &e" + listing.getAmount()),
                color("&7单价: &e" + plugin.getEconomyService().format(listing.getPricePerUnit())),
                color("&c点击管理/下架")));
            display.setItemMeta(meta);
            inv.setItem(i, display);
            holder.actions.put(i, listing.getId());
        }
        inv.setItem(49, item(Material.ARROW, "&f返回商店", List.of()));
        player.openInventory(inv);
    }

    private void openShopHistory(Player player) {
        List<ShopTransaction> records = plugin.getShopManager().getRecentTransactions(player.getUniqueId(), 7, 45);
        MenuHolder holder = new MenuHolder("shop_history", 0, null, 0);
        Inventory inv = create(holder, 54, "&b近 7 日交易记录");
        for (int i = 0; i < records.size() && i < 45; i++) {
            ShopTransaction tx = records.get(i);
            boolean bought = player.getName().equals(tx.buyerName());
            String direction = bought ? "&a买入" : "&e卖出";
            inv.setItem(i, item(bought ? Material.CHEST : Material.EMERALD,
                direction + " &f" + tx.itemName(), List.of(
                    "&7数量: &e" + tx.amount(),
                    "&7单价: &e" + plugin.getEconomyService().format(tx.unitPrice()),
                    "&7合计: &6" + plugin.getEconomyService().format(tx.totalPrice()),
                    "&7对方: &f" + (bought ? tx.sellerName() : tx.buyerName())
                )));
        }
        inv.setItem(49, item(Material.ARROW, "&f返回商店", List.of()));
        player.openInventory(inv);
    }

    private void openPurchase(Player player, String listingId, int requestedAmount) {
        ShopItem listing = plugin.getShopManager().getListings().get(listingId);
        if (listing == null) {
            player.sendMessage("§c商品已经售出或下架");
            openShop(player);
            return;
        }
        int amount = clamp(requestedAmount, 1, listing.getAmount());
        MenuHolder holder = new MenuHolder("shop_buy", 0, listingId, amount);
        Inventory inv = create(holder, 36, "&a确认购买");
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        ItemStack display = listing.toItemStack();
        if (display == null) display = new ItemStack(Material.BARRIER);
        display.setAmount(Math.min(display.getMaxStackSize(), amount));
        ItemMeta meta = display.getItemMeta();
        meta.setLore(List.of(
            color("&7购买数量: &e" + amount),
            color("&7合计: &6" + plugin.getEconomyService().format(amount * listing.getPricePerUnit()))
        ));
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(19, item(Material.RED_DYE, "&c-10", List.of()));
        inv.setItem(20, item(Material.RED_DYE, "&c-1", List.of()));
        inv.setItem(22, item(Material.EMERALD_BLOCK, "&a确认购买", List.of("&7花费: &e" + plugin.getEconomyService().format(amount * listing.getPricePerUnit()))));
        int affordable = listing.getPricePerUnit() <= 0 ? amount
            : (int) Math.min(listing.getAmount(), Math.max(1, Math.floor(plugin.getEconomyService().getBalance(player) / listing.getPricePerUnit())));
        inv.setItem(23, item(Material.BLUE_DYE, "&b购买可负担数量", List.of("&7数量: &e" + affordable, "&e点击设置")));
        inv.setItem(24, item(Material.LIME_DYE, "&a+1", List.of()));
        inv.setItem(25, item(Material.LIME_DYE, "&a+10", List.of()));
        inv.setItem(27, item(Material.ARROW, "&f返回商店", List.of()));
        if (player.getUniqueId().equals(listing.getSellerUuid())) {
            inv.setItem(31, item(Material.LAVA_BUCKET, "&c下架商品", List.of("&7物品将退回背包")));
        }
        player.openInventory(inv);
    }

    private void openListing(Player player, ListingSession existing) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (existing == null && (held.getType().isAir() || held.getAmount() <= 0)) {
            player.sendMessage("§c请先把要上架的物品拿在主手");
            return;
        }
        ListingSession session = existing != null ? existing : new ListingSession(
            held.clone(), held.getAmount(), plugin.getConfig().getDouble("shop.default-price-per-unit", 10.0)
        );
        listingSessions.put(player.getUniqueId(), session);
        MenuHolder holder = new MenuHolder("shop_list", 0, null, 0);
        Inventory inv = create(holder, 45, "&a上架商品");
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        ItemStack display = session.item.clone();
        display.setAmount(Math.min(display.getMaxStackSize(), session.amount));
        ItemMeta meta = display.getItemMeta();
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(color("&7数量: &e" + session.amount));
        lore.add(color("&7单价: &e" + plugin.getEconomyService().format(session.price)));
        lore.add(color("&7手续费: &c" + plugin.getEconomyService().format(session.amount * session.price * plugin.getConfig().getDouble("shop.listing-fee", 0.05))));
        meta.setLore(lore);
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(19, item(Material.RED_DYE, "&c单价 -10", List.of()));
        inv.setItem(20, item(Material.RED_DYE, "&c单价 -1", List.of()));
        inv.setItem(21, item(Material.RED_DYE, "&c数量 -1", List.of()));
        inv.setItem(23, item(Material.LIME_DYE, "&a数量 +1", List.of()));
        inv.setItem(24, item(Material.LIME_DYE, "&a单价 +1", List.of()));
        inv.setItem(25, item(Material.LIME_DYE, "&a单价 +10", List.of()));
        inv.setItem(31, item(Material.EMERALD_BLOCK, "&a确认上架", List.of("&7总价: &e" + plugin.getEconomyService().format(session.amount * session.price))));
        inv.setItem(36, item(Material.ARROW, "&f返回商店", List.of()));
        player.openInventory(inv);
    }

    public void openWaypoints(Player player) {
        MenuHolder holder = new MenuHolder("waypoint", 0, null, 0);
        Inventory inv = create(holder, 54, "&b个人传送点");
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        List<Waypoint> waypoints = plugin.getWaypointManager().getWaypoints(player.getUniqueId());
        int max = Math.min(45, plugin.getConfig().getInt("waypoint.max-waypoints", 18));
        for (int i = 0; i < max; i++) inv.setItem(i, null);
        for (Waypoint wp : waypoints) {
            if (wp.getSlot() < 0 || wp.getSlot() >= max) continue;
            Material icon = material(wp.getIcon(), Material.RECOVERY_COMPASS);
            inv.setItem(wp.getSlot(), item(icon, "&b" + wp.getName(), List.of(
                "&7世界: &f" + wp.getWorld(),
                "&7坐标: &f" + (int) wp.getX() + ", " + (int) wp.getY() + ", " + (int) wp.getZ(),
                "&e左键传送", "&cShift+左键删除"
            )));
            holder.actions.put(wp.getSlot(), String.valueOf(wp.getSlot()));
        }
        inv.setItem(48, item(Material.EMERALD, "&a保存当前位置", List.of("&7费用: &e" + money("waypoint.prices.add"), "&e点击创建")));
        String selectedIcon = waypointIcons.getOrDefault(player.getUniqueId(), "RECOVERY_COMPASS");
        inv.setItem(47, item(material(selectedIcon, Material.RECOVERY_COMPASS), "&b选择图标", List.of("&7当前: &f" + selectedIcon, "&e点击切换")));
        inv.setItem(49, item(Material.ARROW, "&f返回功能中心", List.of()));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        switch (holder.type) {
            case "main" -> handleMain(player, slot);
            case "pass" -> handlePass(player, holder, slot);
            case "pass_reward" -> handlePassReward(player, holder, slot);
            case "quest" -> handleQuest(player, holder, slot);
            case "shop" -> handleShop(player, holder, slot);
            case "shop_mine" -> handleShopMine(player, holder, slot);
            case "shop_history" -> handleShopHistory(player, slot);
            case "shop_buy" -> handlePurchase(player, holder, slot);
            case "shop_list" -> handleListing(player, slot);
            case "waypoint" -> handleWaypoint(player, holder, slot, event.isShiftClick());
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder holder
            && holder.type.equals("shop_list") && event.getPlayer() instanceof Player player) {
            listingSessions.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        listingSessions.remove(event.getPlayer().getUniqueId());
        waypointIcons.remove(event.getPlayer().getUniqueId());
    }

    private void handleMain(Player player, int slot) {
        switch (slot) {
            case 10 -> openPass(player);
            case 12 -> openQuests(player, QuestType.DAILY);
            case 14 -> openShop(player);
            case 16 -> openWaypoints(player);
            case 40 -> player.closeInventory();
            default -> { }
        }
    }

    private void handlePass(Player player, MenuHolder holder, int slot) {
        if (holder.actions.containsKey(slot)) {
            openPassReward(player, Integer.parseInt(holder.actions.get(slot)));
        } else if (slot == 45) openPass(player, holder.page - 1);
        else if (slot == 53) openPass(player, holder.page + 1);
        else if (slot == 49) openMain(player);
        else if (slot == 50) {
            plugin.getPassManager().upgradeTier(player, TierType.PREMIUM);
            openPass(player, holder.page);
        } else if (slot == 51) {
            plugin.getPassManager().upgradeTier(player, TierType.VIP);
            openPass(player, holder.page);
        }
    }

    private void handlePassReward(Player player, MenuHolder holder, int slot) {
        TierType tier = switch (slot) {
            case 11 -> TierType.FREE;
            case 13 -> TierType.PREMIUM;
            case 15 -> TierType.VIP;
            default -> null;
        };
        if (tier != null) {
            plugin.getPassManager().claimReward(player, holder.value, tier);
            openPassReward(player, holder.value);
        } else if (slot == 22) openPass(player, Math.max(0, (holder.value - 1) / PAGE_SIZE));
    }

    private void handleQuest(Player player, MenuHolder holder, int slot) {
        QuestType current = QuestType.valueOf(holder.ref);
        if (slot >= 0 && slot <= 4) {
            openQuests(player, QuestType.values()[slot]);
        } else if (holder.actions.containsKey(slot)) {
            QuestProgress progress = plugin.getQuestManager().getPlayerData(player.getUniqueId())
                .getProgress(holder.actions.get(slot));
            if (progress != null && progress.getStatus() == QuestStatus.COMPLETED) {
                plugin.getQuestManager().claimQuest(player, holder.actions.get(slot));
                openQuests(player, current, holder.page);
            }
        } else if (slot == 45) openQuests(player, current, holder.page - 1);
        else if (slot == 53) openQuests(player, current, holder.page + 1);
        else if (slot == 49) openMain(player);
    }

    private void handleShop(Player player, MenuHolder holder, int slot) {
        String listing = holder.actions.get(slot);
        if (listing != null) openPurchase(player, listing, 1);
        else if (slot == 45) openShop(player, holder.page - 1);
        else if (slot == 53) openShop(player, holder.page + 1);
        else if (slot == 47) openMyShop(player);
        else if (slot == 48) openShopHistory(player);
        else if (slot == 49) openListing(player, null);
        else if (slot == 50) openMain(player);
    }

    private void handleShopMine(Player player, MenuHolder holder, int slot) {
        String listing = holder.actions.get(slot);
        if (listing != null) openPurchase(player, listing, 1);
        else if (slot == 49) openShop(player);
    }

    private void handleShopHistory(Player player, int slot) {
        if (slot == 49) openShop(player);
    }

    private void handlePurchase(Player player, MenuHolder holder, int slot) {
        ShopItem listing = plugin.getShopManager().getListings().get(holder.ref);
        if (listing == null) {
            openShop(player);
            return;
        }
        int amount = holder.value;
        switch (slot) {
            case 19 -> openPurchase(player, holder.ref, amount - 10);
            case 20 -> openPurchase(player, holder.ref, amount - 1);
            case 23 -> {
                int affordable = (int) Math.min(listing.getAmount(), Math.max(1,
                    Math.floor(plugin.getEconomyService().getBalance(player) / listing.getPricePerUnit())));
                openPurchase(player, holder.ref, affordable);
            }
            case 24 -> openPurchase(player, holder.ref, amount + 1);
            case 25 -> openPurchase(player, holder.ref, amount + 10);
            case 22 -> {
                player.closeInventory();
                plugin.getShopManager().buyItem(player, holder.ref, amount);
            }
            case 27 -> openShop(player);
            case 31 -> {
                if (player.getUniqueId().equals(listing.getSellerUuid())) {
                    player.closeInventory();
                    plugin.getShopManager().delistItem(player, holder.ref);
                }
            }
            default -> { }
        }
    }

    private void handleListing(Player player, int slot) {
        ListingSession session = listingSessions.get(player.getUniqueId());
        if (session == null) {
            openShop(player);
            return;
        }
        switch (slot) {
            case 19 -> session.price = Math.max(0.01, session.price - 10);
            case 20 -> session.price = Math.max(0.01, session.price - 1);
            case 21 -> session.amount = Math.max(1, session.amount - 1);
            case 23 -> session.amount = Math.min(plugin.getConfig().getInt("shop.max-listing-amount", 128), session.amount + 1);
            case 24 -> session.price = Math.min(plugin.getConfig().getDouble("shop.max-price-per-unit", 100000), session.price + 1);
            case 25 -> session.price = Math.min(plugin.getConfig().getDouble("shop.max-price-per-unit", 100000), session.price + 10);
            case 31 -> {
                listingSessions.remove(player.getUniqueId());
                player.closeInventory();
                plugin.getShopManager().listItem(player, session.item, session.amount, session.price);
                return;
            }
            case 36 -> {
                listingSessions.remove(player.getUniqueId());
                openShop(player);
                return;
            }
            default -> { return; }
        }
        openListing(player, session);
    }

    private void handleWaypoint(Player player, MenuHolder holder, int slot, boolean shift) {
        String waypointSlot = holder.actions.get(slot);
        if (waypointSlot != null) {
            int id = Integer.parseInt(waypointSlot);
            player.closeInventory();
            if (shift) plugin.getWaypointManager().deleteWaypoint(player, id);
            else plugin.getWaypointManager().teleport(player, id);
        } else if (slot == 47) {
            String current = waypointIcons.getOrDefault(player.getUniqueId(), WAYPOINT_ICONS.get(0).name());
            int index = 0;
            for (int i = 0; i < WAYPOINT_ICONS.size(); i++) if (WAYPOINT_ICONS.get(i).name().equals(current)) index = i;
            waypointIcons.put(player.getUniqueId(), WAYPOINT_ICONS.get((index + 1) % WAYPOINT_ICONS.size()).name());
            openWaypoints(player);
        } else if (slot == 48) {
            String icon = waypointIcons.getOrDefault(player.getUniqueId(), WAYPOINT_ICONS.get(0).name());
            plugin.getWaypointManager().addWaypoint(player, player.getLocation(), icon, "传送点 " + (plugin.getWaypointManager().getWaypoints(player.getUniqueId()).size() + 1));
            openWaypoints(player);
        } else if (slot == 49) openMain(player);
    }

    private void putTierReward(Inventory inv, int slot, PassConfig config, PlayerPassData data, int level, TierType tier, Material material) {
        List<String> rewards = config.getRewardsForLevel(level, tier);
        List<String> lore = new ArrayList<>();
        if (rewards.isEmpty()) lore.add("&7该档位此等级无奖励");
        else rewards.forEach(reward -> lore.add("&f- " + rewardLabel(reward)));
        lore.add("");
        if (data.hasClaimed(level, tier)) lore.add("&a已领取");
        else if (!data.canClaimReward(level, tier)) lore.add("&c尚未解锁");
        else lore.add("&e点击领取");
        inv.setItem(slot, item(material, tierName(tier), lore));
    }

    private void appendRewardState(List<String> lore, PassConfig config, PlayerPassData data, int level, TierType tier) {
        if (config.getRewardsForLevel(level, tier).isEmpty()) return;
        String state = data.hasClaimed(level, tier) ? "&a已领取" : data.canClaimReward(level, tier) ? "&e可领取" : "&7未解锁";
        lore.add(tierName(tier) + " &8- " + state);
    }

    private boolean hasRewards(PassConfig config, int level) {
        for (TierType tier : TierType.values()) if (!config.getRewardsForLevel(level, tier).isEmpty()) return true;
        return false;
    }

    private ItemStack typeButton(QuestType button, QuestType selected) {
        return item(button == selected ? Material.LIME_DYE : Material.GRAY_DYE, questTypeName(button), List.of(button == selected ? "&a当前分类" : "&e点击切换"));
    }

    private String objectiveName(QuestConfig.Objective objective) {
        return switch (objective.getConditionType().toUpperCase()) {
            case "BLOCK_BREAK" -> "破坏 " + objective.getTarget();
            case "ENTITY_KILL" -> "击杀 " + objective.getTarget();
            case "ITEM_CRAFT" -> "合成 " + objective.getTarget();
            case "ITEM_CONSUME" -> "使用 " + objective.getTarget();
            default -> objective.getTarget();
        };
    }

    private String rewardLabel(String reward) {
        int split = reward.indexOf(':');
        if (split < 0) return reward;
        String type = reward.substring(0, split).toLowerCase();
        String value = reward.substring(split + 1);
        return switch (type) {
            case "money" -> "金币 " + value;
            case "exp" -> "通行证经验 " + value;
            case "item" -> "物品 " + value;
            case "permission" -> "限时权限 " + value;
            case "command" -> "服务器奖励";
            case "title" -> "称号 " + value;
            default -> reward;
        };
    }

    private void navigation(Inventory inv, int page, int maxPage) {
        if (page > 0) inv.setItem(45, item(Material.ARROW, "&e上一页", List.of()));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "&e下一页", List.of()));
    }

    private Inventory create(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, color(title));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inv, Material material) {
        ItemStack filler = item(material, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private String tierName(TierType tier) {
        return switch (tier) {
            case FREE -> "&7免费档";
            case PREMIUM -> "&6高级档";
            case VIP -> "&d至尊档";
        };
    }

    private String questTypeName(QuestType type) {
        return switch (type) {
            case DAILY -> "&e每日任务";
            case WEEKLY -> "&b每周任务";
            case SEASONAL -> "&6赛季任务";
            case STORY -> "&d剧情任务";
            case CHALLENGE -> "&c挑战任务";
        };
    }

    private String money(String path) {
        return plugin.getEconomyService().format(plugin.getConfig().getDouble(path));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private Material material(String name, Material fallback) {
        try { return Material.valueOf(name.toUpperCase()); }
        catch (Exception ignored) { return fallback; }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatDuration(long millis) {
        long days = millis / 86_400_000L;
        long hours = (millis % 86_400_000L) / 3_600_000L;
        return days + " 天 " + hours + " 小时";
    }

    private static final class MenuHolder implements InventoryHolder {
        private final String type;
        private final int page;
        private final String ref;
        private final int value;
        private final Map<Integer, String> actions = new HashMap<>();
        private Inventory inventory;

        private MenuHolder(String type, int page, String ref, int value) {
            this.type = type;
            this.page = page;
            this.ref = ref;
            this.value = value;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ListingSession {
        private final ItemStack item;
        private int amount;
        private double price;

        private ListingSession(ItemStack item, int amount, double price) {
            this.item = item;
            this.item.setAmount(1);
            this.amount = amount;
            this.price = price;
        }
    }
}
