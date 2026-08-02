package com.ethercats.siyuan.core.service;

import com.ethercats.siyuan.core.DatabaseManager;
import com.ethercats.siyuan.core.RedisManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyService {
    private Economy economy;
    private final JavaPlugin plugin;
    private final RedisManager redis;
    private final DatabaseManager db;
    private final Map<String, Long> localDailyRewards = new ConcurrentHashMap<>();

    public EconomyService(JavaPlugin plugin, RedisManager redis, DatabaseManager db) {
        this.plugin = plugin;
        this.redis = redis;
        this.db = db;
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().severe("[Economy] 未找到 Vault，经济功能禁用");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().severe("[Economy] 未找到经济提供者");
            return false;
        }
        economy = rsp.getProvider();
        plugin.getLogger().info("[Economy] 已挂钩: " + economy.getName());
        return true;
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        return withdrawRaw(player, amount);
    }

    public void deposit(OfflinePlayer player, double amount) {
        depositRaw(player, amount);
    }

    /** Returns currency from a failed or reversible operation and records the source. */
    public boolean refund(OfflinePlayer player, double amount, String reason) {
        if (!depositRaw(player, amount)) return false;
        // A refund reverses a previous sink; it must not look like new money
        // in the inflation snapshot. It remains visible in the audit stream.
        audit("REVERSAL_IN", amount, reason);
        return true;
    }

    /** Removes currency from a player and records it as a deliberate sink. */
    public boolean sink(OfflinePlayer player, double amount, String reason) {
        if (!withdrawRaw(player, amount)) return false;
        recordMoneyOut(amount, reason);
        return true;
    }

    /** Creates currency for a reward, subject to the configured daily cap. */
    public double mintReward(OfflinePlayer player, double requested, String reason) {
        if (requested <= 0 || !Double.isFinite(requested) || economy == null) return 0;
        double cap = plugin.getConfig().getDouble("economy.max-daily-reward-per-player", 5000.0);
        String key = LocalDate.now() + ":" + player.getUniqueId();
        long requestedCents = Math.max(0, Math.round(requested * 100));
        if (!Double.isFinite(cap)) return 0;
        long capCents = Math.max(0, Math.round(cap * 100));
        if (requestedCents <= 0 || capCents <= 0) return 0;
        long allowedCents;
        String quotaKey = "sy:economy:reward:" + key;
        if (redis.isEnabled()) {
            // Redis failures fail closed; silently treating a missing value as
            // zero would allow unlimited reward minting during an outage.
            allowedCents = redis.consumeQuota(quotaKey, requestedCents, capCents, 172800);
            if (allowedCents < 0) return 0;
        } else {
            synchronized (localDailyRewards) {
                if (localDailyRewards.size() > 10_000) {
                    String today = LocalDate.now().toString();
                    localDailyRewards.keySet().removeIf(existing -> !existing.startsWith(today));
                }
                long oldCents = localDailyRewards.getOrDefault(key, 0L);
                allowedCents = Math.max(0, Math.min(requestedCents, capCents - oldCents));
                if (allowedCents > 0) localDailyRewards.put(key, oldCents + allowedCents);
            }
        }
        double allowed = allowedCents / 100.0;
        if (allowed <= 0) return 0;
        if (!depositRaw(player, allowed)) {
            if (redis.isEnabled()) redis.releaseQuota(quotaKey, allowedCents, 172800);
            else synchronized (localDailyRewards) { localDailyRewards.merge(key, -allowedCents, Long::sum); }
            return 0;
        }
        recordMoneyIn(allowed, reason);
        return allowed;
    }

    /** Moves currency between accounts without changing total money supply. */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount, String reason) {
        if (!withdrawRaw(from, amount)) return false;
        if (!depositRaw(to, amount)) {
            if (!depositRaw(from, amount)) {
                plugin.getLogger().severe("[Economy] 转账补偿失败，需人工对账: " + from.getUniqueId() + " amount=" + amount);
                audit("COMPENSATION_PENDING", amount, reason);
            }
            return false;
        }
        recordTrade(amount, reason);
        return true;
    }

    public double getBalance(OfflinePlayer player) {
        return economy == null ? 0 : economy.getBalance(player);
    }

    public String format(double amount) {
        return economy == null ? String.format("%.2f", amount) : economy.format(amount);
    }

    // 通胀监控：记录货币流入
    public void recordMoneyIn(double amount, String type) {
        if (amount <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String date = LocalDate.now().toString();
            String key = "sy:economy:daily:" + date;
            redis.hincrby(key, "money_in", Math.round(amount * 100));
            redis.expire(key, 691200); // 8天
            audit("IN", amount, type);
        });
    }

    // 通胀监控：记录货币流出
    public void recordMoneyOut(double amount, String type) {
        if (amount <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String date = LocalDate.now().toString();
            String key = "sy:economy:daily:" + date;
            redis.hincrby(key, "money_out", Math.round(amount * 100));
            redis.expire(key, 691200);
            audit("OUT", amount, type);
        });
    }

    // 每日凌晨 dump 到 MySQL
    public void dumpDailySnapshot() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        String key = "sy:economy:daily:" + yesterday;
        
        String in = redis.hget(key, "money_in");
        String out = redis.hget(key, "money_out");
        String trades = redis.hget(key, "trade_count");
        
        if (in == null && out == null && trades == null) return;
        
        double moneyIn = in == null ? 0 : Long.parseLong(in) / 100.0;
        double moneyOut = out == null ? 0 : Long.parseLong(out) / 100.0;
        int tradeCount = trades == null ? 0 : Integer.parseInt(trades);
        
        String sql = "INSERT INTO sy_economy_snapshot (snapshot_date, total_money_in, total_money_out, total_trades, avg_item_price, active_players) "
                   + "VALUES (?, ?, ?, ?, 0, 0) ON DUPLICATE KEY UPDATE total_money_in=?, total_money_out=?, total_trades=?";
        db.execute(sql, yesterday, moneyIn, moneyOut, tradeCount, moneyIn, moneyOut, tradeCount);
        plugin.getLogger().info("[Economy] 快照已保存: " + yesterday);
    }

    public boolean isAvailable() {
        return economy != null;
    }

    private boolean withdrawRaw(OfflinePlayer player, double amount) {
        if (economy == null || player == null || !Double.isFinite(amount) || amount <= 0) return false;
        if (economy.getBalance(player) + 1.0e-8 < amount) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    private boolean depositRaw(OfflinePlayer player, double amount) {
        if (economy == null || player == null || !Double.isFinite(amount) || amount <= 0) return false;
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    private void recordTrade(double amount, String type) {
        if (amount <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String date = LocalDate.now().toString();
            String key = "sy:economy:daily:" + date;
            redis.hincrby(key, "trade_volume", Math.round(amount * 100));
            redis.hincrby(key, "trade_count", 1);
            redis.expire(key, 691200);
        });
    }

    private void audit(String direction, double amount, String reason) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.execute(
            "INSERT INTO sy_economy_events (direction, amount, reason, occurred_at) VALUES (?, ?, ?, ?)",
            direction, amount, reason, System.currentTimeMillis()
        ));
    }
}
