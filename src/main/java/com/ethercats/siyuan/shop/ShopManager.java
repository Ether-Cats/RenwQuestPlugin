package com.ethercats.siyuan.shop;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShopManager {
    private final SiYuanPlugin plugin;
    private final DatabaseManager db;
    private final Map<String, ShopItem> listings = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[256];

    public ShopManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        loadAll();
    }

    private void loadAll() {
        listings.clear();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            db.query("SELECT * FROM sy_shop_items ORDER BY listed_at DESC LIMIT 500", rs -> {
                while (rs.next()) {
                    ShopItem item = new ShopItem(
                        rs.getString("id"), UUID.fromString(rs.getString("seller_uuid")), rs.getString("seller_name"),
                        rs.getString("item_base64"), rs.getString("item_name"), rs.getInt("amount"),
                        rs.getDouble("price_per_unit"), rs.getLong("listed_at")
                    );
                    listings.put(item.getId(), item);
                }
                return null;
            });
            plugin.getLogger().info("[Shop] 已加载 " + listings.size() + " 个商品");
        });
    }

    public void listItem(Player seller, ItemStack requestedTemplate, int amount, double pricePerUnit) {
        if (requestedTemplate == null || requestedTemplate.getType().isAir()) {
            seller.sendMessage("§c无效物品");
            return;
        }
        int maxAmount = plugin.getConfig().getInt("shop.max-listing-amount", 128);
        int maxListings = plugin.getConfig().getInt("shop.max-listings-per-player", 16);
        double maxPrice = plugin.getConfig().getDouble("shop.max-price-per-unit", 100000);
        if (amount < 1 || amount > maxAmount) {
            seller.sendMessage("§c上架数量必须在 1 到 " + maxAmount + " 之间");
            return;
        }
        if (!Double.isFinite(pricePerUnit) || pricePerUnit <= 0 || pricePerUnit > maxPrice) {
            seller.sendMessage("§c单价必须在 0 到 " + maxPrice + " 之间");
            return;
        }
        Integer storedListings = db.query("SELECT COUNT(*) FROM sy_shop_items WHERE seller_uuid=? AND amount>0",
            rs -> rs.next() ? rs.getInt(1) : 0, seller.getUniqueId().toString());
        long ownListings = storedListings == null
            ? listings.values().stream().filter(i -> i.getSellerUuid().equals(seller.getUniqueId())).count()
            : storedListings;
        if (ownListings >= maxListings) {
            seller.sendMessage("§c你的上架商品已达到上限: " + maxListings);
            return;
        }

        ItemStack template = requestedTemplate.clone();
        template.setAmount(1);
        if (!hasItems(seller, template, amount)) {
            seller.sendMessage("§c背包中没有足够的物品");
            return;
        }

        double fee = amount * pricePerUnit * plugin.getConfig().getDouble("shop.listing-fee", 0.05);
        if (fee > 0 && !plugin.getEconomyService().sink(seller, fee, "SHOP_LISTING_FEE")) {
            plugin.getMessageService().send(seller, "shop.no-money", plugin.getEconomyService().format(fee), plugin.getEconomyService().format(plugin.getEconomyService().getBalance(seller)));
            return;
        }
        if (!deductItems(seller, template, amount)) {
            if (fee > 0) plugin.getEconomyService().refund(seller, fee, "SHOP_LISTING_ROLLBACK");
            seller.sendMessage("§c扣除物品失败，操作已回滚");
            return;
        }

        String base64 = ShopItem.serializeItem(template);
        if (base64 == null) {
            restoreItems(seller, template, amount);
            if (fee > 0) plugin.getEconomyService().refund(seller, fee, "SHOP_LISTING_ROLLBACK");
            seller.sendMessage("§c物品数据序列化失败，操作已回滚");
            return;
        }
        String itemName = template.hasItemMeta() && template.getItemMeta().hasDisplayName()
            ? template.getItemMeta().getDisplayName() : template.getType().name();
        String id = UUID.randomUUID().toString();
        ShopItem listing = new ShopItem(id, seller.getUniqueId(), seller.getName(), base64, itemName, amount, pricePerUnit, System.currentTimeMillis());
        int inserted = db.execute(
            "INSERT INTO sy_shop_items (id, seller_uuid, seller_name, item_base64, item_name, amount, price_per_unit, listed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, seller.getUniqueId().toString(), seller.getName(), base64, itemName, amount, pricePerUnit, listing.getListedAt()
        );
        if (inserted != 1) {
            restoreItems(seller, template, amount);
            if (fee > 0) plugin.getEconomyService().refund(seller, fee, "SHOP_LISTING_ROLLBACK");
            seller.sendMessage("§c商品保存失败，操作已回滚");
            return;
        }
        listings.put(id, listing);
        plugin.getMessageService().send(seller, "shop.list-success", amount, plugin.getEconomyService().format(pricePerUnit));
        plugin.getMessageService().sendRaw(seller, "shop.listing-fee", plugin.getEconomyService().format(fee));
    }

    public void buyItem(Player buyer, String itemId) {
        ShopItem listing = listings.get(itemId);
        buyItem(buyer, itemId, listing == null ? 1 : listing.getAmount());
    }

    public void buyItem(Player buyer, String itemId, int requestedAmount) {
        Object lock = lockFor(itemId);
        synchronized (lock) {
            ShopItem listing = listings.get(itemId);
            if (listing == null || listing.getAmount() <= 0) {
                buyer.sendMessage("§c商品不存在或已售出");
                return;
            }
            if (buyer.getUniqueId().equals(listing.getSellerUuid())) {
                plugin.getMessageService().send(buyer, "shop.own-item");
                return;
            }
            int amount = Math.max(1, Math.min(requestedAmount, listing.getAmount()));
            ItemStack template = listing.toItemStack();
            if (template == null) {
                buyer.sendMessage("§c物品数据损坏，请联系管理员");
                return;
            }
            template.setAmount(1);
            if (!canFit(buyer.getInventory(), template, amount)) {
                plugin.getMessageService().send(buyer, "shop.inv-full");
                return;
            }
            double totalPrice = amount * listing.getPricePerUnit();
            int reserved = db.execute("UPDATE sy_shop_items SET amount=amount-? WHERE id=? AND amount>=?", amount, itemId, amount);
            if (reserved != 1) {
                Integer current = db.query("SELECT amount FROM sy_shop_items WHERE id=?", rs -> rs.next() ? rs.getInt(1) : 0, itemId);
                if (current == null || current <= 0) listings.remove(itemId);
                else listing.setAmount(current);
                buyer.sendMessage("§c商品库存刚刚发生变化，请刷新商店");
                return;
            }
            ItemStack[] inventoryBefore = snapshotStorage(buyer.getInventory());
            List<ItemStack> leftovers = addItems(buyer, template, amount);
            if (!leftovers.isEmpty()) {
                restoreStorage(buyer, inventoryBefore);
                db.execute("UPDATE sy_shop_items SET amount=amount+? WHERE id=?", amount, itemId);
                plugin.getMessageService().send(buyer, "shop.inv-full");
                return;
            }
            OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.getSellerUuid());
            if (!plugin.getEconomyService().transfer(buyer, seller, totalPrice, "SHOP_TRADE")) {
                restoreStorage(buyer, inventoryBefore);
                db.execute("UPDATE sy_shop_items SET amount=amount+? WHERE id=?", amount, itemId);
                plugin.getMessageService().send(buyer, "shop.no-money", plugin.getEconomyService().format(totalPrice), plugin.getEconomyService().format(plugin.getEconomyService().getBalance(buyer)));
                return;
            }

            Integer remainingStock = db.query("SELECT amount FROM sy_shop_items WHERE id=?", rs -> rs.next() ? rs.getInt(1) : 0, itemId);
            listing.setAmount(remainingStock == null ? listing.getAmount() - amount : remainingStock);
            if (listing.getAmount() <= 0) {
                db.execute("DELETE FROM sy_shop_items WHERE id=? AND amount=0", itemId);
                listings.remove(itemId);
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.execute(
                "INSERT INTO sy_transactions (buyer_uuid, buyer_name, seller_uuid, seller_name, item_name, amount, unit_price, total_price, tx_type, tx_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SHOP', ?)",
                buyer.getUniqueId().toString(), buyer.getName(), listing.getSellerUuid().toString(), listing.getSellerName(), listing.getItemName(), amount, listing.getPricePerUnit(), totalPrice, System.currentTimeMillis()
            ));
            plugin.getMessageService().send(buyer, "shop.buy-success", plugin.getEconomyService().format(totalPrice), amount, listing.getItemName());
            Player onlineSeller = Bukkit.getPlayer(listing.getSellerUuid());
            if (onlineSeller != null) onlineSeller.sendMessage("§a你的商品售出 " + amount + " 个，到账 " + plugin.getEconomyService().format(totalPrice));
        }
    }

    public void delistItem(Player seller, String itemId) {
        Object lock = lockFor(itemId);
        synchronized (lock) {
            ShopItem listing = listings.get(itemId);
            if (listing == null || !listing.getSellerUuid().equals(seller.getUniqueId())) {
                seller.sendMessage("§c商品不存在或不属于你");
                return;
            }
            ItemStack template = listing.toItemStack();
            if (template == null) {
                seller.sendMessage("§c商品数据损坏，已阻止下架，请联系管理员处理");
                return;
            }
            if (db.execute("DELETE FROM sy_shop_items WHERE id=? AND seller_uuid=?", itemId, seller.getUniqueId().toString()) != 1) {
                seller.sendMessage("§c下架失败，请稍后重试");
                return;
            }
            listings.remove(itemId);
            template.setAmount(1);
            addItems(seller, template, listing.getAmount()).forEach(item ->
                seller.getWorld().dropItemNaturally(seller.getLocation(), item));
            plugin.getMessageService().send(seller, "shop.delist-success");
        }
    }

    public void reload() { loadAll(); }
    public void saveAll() { }
    public void openGUI(Player player) { plugin.getGuiManager().openShop(player); }
    public Map<String, ShopItem> getListings() { return listings; }

    public List<ShopItem> getListingsForSeller(UUID sellerUuid) {
        return listings.values().stream()
            .filter(item -> item.getSellerUuid().equals(sellerUuid) && item.getAmount() > 0)
            .sorted(java.util.Comparator.comparingLong(ShopItem::getListedAt).reversed())
            .toList();
    }

    public List<ShopTransaction> getRecentTransactions(UUID playerUuid, int days, int limit) {
        long since = System.currentTimeMillis() - Math.max(1, days) * 86_400_000L;
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<ShopTransaction> result = new ArrayList<>();
        db.query(
            "SELECT buyer_name, seller_name, item_name, amount, unit_price, total_price, tx_at "
                + "FROM sy_transactions WHERE (buyer_uuid=? OR seller_uuid=?) AND tx_at>=? "
                + "ORDER BY tx_at DESC LIMIT " + safeLimit,
            rs -> {
                while (rs.next()) {
                    result.add(new ShopTransaction(rs.getString("buyer_name"), rs.getString("seller_name"),
                        rs.getString("item_name"), rs.getInt("amount"), rs.getDouble("unit_price"),
                        rs.getDouble("total_price"), rs.getLong("tx_at")));
                }
                return null;
            }, playerUuid.toString(), playerUuid.toString(), since
        );
        return result;
    }

    private boolean hasItems(Player player, ItemStack template, int amount) {
        int found = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(template)) found += item.getAmount();
            if (found >= amount) return true;
        }
        return false;
    }

    private boolean deductItems(Player player, ItemStack template, int amount) {
        if (!hasItems(player, template, amount)) return false;
        int remaining = amount;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.isSimilar(template)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (remaining == 0) break;
        }
        player.updateInventory();
        return true;
    }

    private void restoreItems(Player player, ItemStack template, int amount) {
        addItems(player, template, amount).forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private boolean canFit(Inventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) remaining -= template.getMaxStackSize();
            else if (current.isSimilar(template)) remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    private List<ItemStack> addItems(Player player, ItemStack template, int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int stackAmount = Math.min(remaining, template.getMaxStackSize());
            ItemStack stack = template.clone();
            stack.setAmount(stackAmount);
            stacks.add(stack);
            remaining -= stackAmount;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack stack : stacks) leftovers.addAll(player.getInventory().addItem(stack).values());
        return leftovers;
    }

    private void removeItems(Player player, ItemStack template, int amount) {
        int remaining = amount;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || !item.isSimilar(template)) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (remaining == 0) break;
        }
        player.updateInventory();
    }

    private ItemStack[] snapshotStorage(Inventory inventory) {
        ItemStack[] current = inventory.getStorageContents();
        ItemStack[] snapshot = new ItemStack[current.length];
        for (int i = 0; i < current.length; i++) snapshot[i] = current[i] == null ? null : current[i].clone();
        return snapshot;
    }

    private void restoreStorage(Player player, ItemStack[] snapshot) {
        ItemStack[] restored = new ItemStack[snapshot.length];
        for (int i = 0; i < snapshot.length; i++) restored[i] = snapshot[i] == null ? null : snapshot[i].clone();
        player.getInventory().setStorageContents(restored);
        player.updateInventory();
    }

    private Object lockFor(String itemId) {
        return locks[(itemId.hashCode() & 0x7fffffff) % locks.length];
    }
}
