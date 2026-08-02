package com.ethercats.siyuan.waypoint;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.core.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WaypointManager {
    
    private final SiYuanPlugin plugin;
    private final DatabaseManager db;
    private final Map<UUID, List<Waypoint>> waypoints = new ConcurrentHashMap<>();
    
    public WaypointManager(SiYuanPlugin plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
    }
    
    public List<Waypoint> loadPlayerWaypoints(UUID uuid) {
        List<Waypoint> list = new ArrayList<>();
        Boolean loaded = db.query("SELECT * FROM sy_waypoints WHERE uuid=? ORDER BY slot", rs -> {
            while (rs.next()) {
                Waypoint wp = new Waypoint(
                    rs.getInt("slot"),
                    rs.getString("world"),
                    rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                    rs.getFloat("yaw"), rs.getFloat("pitch"),
                    rs.getString("icon"),
                    rs.getString("name")
                );
                list.add(wp);
            }
            return Boolean.TRUE;
        }, uuid.toString());
        if (loaded == null) throw new IllegalStateException("无法读取玩家传送点数据: " + uuid);
        waypoints.put(uuid, list);
        return list;
    }
    
    public List<Waypoint> getWaypoints(UUID uuid) {
        return waypoints.computeIfAbsent(uuid, this::loadPlayerWaypoints);
    }
    
    public void addWaypoint(Player player, Location loc, String icon, String name) {
        if (loc == null || loc.getWorld() == null) {
            player.sendMessage("§c当前位置无效");
            return;
        }
        String safeName = name == null || name.isBlank() ? "传送点" : name.trim();
        if (safeName.length() > 64) safeName = safeName.substring(0, 64);
        String safeIcon = icon == null || icon.isBlank() ? "RECOVERY_COMPASS" : icon.trim().toUpperCase();
        List<Waypoint> list = getWaypoints(player.getUniqueId());
        int maxWaypoints = plugin.getConfig().getInt("waypoint.max-waypoints", 18);
        
        if (list.size() >= maxWaypoints) {
            plugin.getMessageService().send(player, "waypoint.full", maxWaypoints);
            return;
        }
        
        double addPrice = plugin.getConfig().getDouble("waypoint.prices.add", 10.0);
        if (!plugin.getEconomyService().sink(player, addPrice, "WAYPOINT_CREATE")) {
            plugin.getMessageService().send(player, "waypoint.no-money");
            return;
        }
        
        // 找空槽位
        Set<Integer> used = new HashSet<>();
        list.forEach(wp -> used.add(wp.getSlot()));
        int slot = -1;
        for (int i = 0; i < maxWaypoints; i++) {
            if (!used.contains(i)) { slot = i; break; }
        }
        
        if (slot == -1) {
            plugin.getEconomyService().refund(player, addPrice, "WAYPOINT_FAILED");
            player.sendMessage("§c槽位已满");
            return;
        }
        
        final int selectedSlot = slot;
        Waypoint wp = Waypoint.fromLocation(loc, selectedSlot, safeIcon, safeName);
        int inserted = db.execute(
            "INSERT INTO sy_waypoints (uuid, slot, world, x, y, z, yaw, pitch, icon, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            player.getUniqueId().toString(), selectedSlot, wp.getWorld(), wp.getX(), wp.getY(), wp.getZ(),
            wp.getYaw(), wp.getPitch(), wp.getIcon(), wp.getName()
        );
        if (inserted != 1) {
            plugin.getEconomyService().refund(player, addPrice, "WAYPOINT_FAILED");
            player.sendMessage("§c传送点保存失败，费用已退回");
            return;
        }
        list.add(wp);
        
        plugin.getMessageService().send(player, "waypoint.add-success", safeName, plugin.getEconomyService().format(addPrice));
    }
    
    public void deleteWaypoint(Player player, int slot) {
        List<Waypoint> list = getWaypoints(player.getUniqueId());
        Waypoint wp = list.stream().filter(w -> w.getSlot() == slot).findFirst().orElse(null);
        if (wp == null) {
            player.sendMessage("§c传送点不存在");
            return;
        }
        
        if (db.execute("DELETE FROM sy_waypoints WHERE uuid=? AND slot=?", player.getUniqueId().toString(), slot) != 1) {
            player.sendMessage("§c传送点删除失败，请稍后重试");
            return;
        }
        list.remove(wp);
        double refund = plugin.getConfig().getDouble("waypoint.prices.refund", 8.0);
        plugin.getEconomyService().refund(player, refund, "WAYPOINT_REFUND");
        
        plugin.getMessageService().send(player, "waypoint.del-success", plugin.getEconomyService().format(refund));
    }
    
    public void teleport(Player player, int slot) {
        List<Waypoint> list = getWaypoints(player.getUniqueId());
        Waypoint wp = list.stream().filter(w -> w.getSlot() == slot).findFirst().orElse(null);
        if (wp == null) {
            player.sendMessage("§c传送点不存在");
            return;
        }
        
        double tpPrice = plugin.getConfig().getDouble("waypoint.prices.teleport", 2.0);
        if (!plugin.getEconomyService().sink(player, tpPrice, "WAYPOINT_TELEPORT")) {
            plugin.getMessageService().send(player, "waypoint.no-money");
            return;
        }
        
        Location loc = wp.toLocation();
        if (loc == null) {
            plugin.getEconomyService().refund(player, tpPrice, "WAYPOINT_FAILED");
            player.sendMessage("§c目标世界未加载");
            return;
        }
        
        player.teleportAsync(loc).thenAccept(result -> {
            if (result) {
                plugin.getMessageService().send(player, "waypoint.tp-success", wp.getName());
                plugin.getMessageService().sendRaw(player, "waypoint.tp-cost", plugin.getEconomyService().format(tpPrice));
            } else {
                plugin.getEconomyService().refund(player, tpPrice, "WAYPOINT_FAILED");
                player.sendMessage("§c传送失败");
            }
        });
    }
    
    public void saveAll() {
        // 已存DB，无需额外保存
    }
    
    public void openGUI(Player player) {
        plugin.getGuiManager().openWaypoints(player);
    }

    public void clearCache() { waypoints.clear(); }
}
