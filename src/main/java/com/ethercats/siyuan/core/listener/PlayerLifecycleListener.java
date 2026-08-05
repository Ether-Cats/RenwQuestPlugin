package com.ethercats.siyuan.core.listener;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerLifecycleListener implements Listener {
    private final SiYuanPlugin plugin;

    public PlayerLifecycleListener(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDb().execute(
                "INSERT INTO sy_players (uuid, name, first_join, last_seen) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name=?, last_seen=?",
                player.getUniqueId().toString(), player.getName(), now, now, player.getName(), now
            );
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        try {
            plugin.getPassManager().savePlayerData(plugin.getPassManager().getPlayerData(player.getUniqueId()));
            plugin.getQuestManager().savePlayerData(plugin.getQuestManager().getPlayerData(player.getUniqueId()));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[Player] 退出时数据未加载成功，跳过覆盖式保存: " + ex.getMessage());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getDb().execute(
            "UPDATE sy_players SET name=?, last_seen=? WHERE uuid=?",
            player.getName(), System.currentTimeMillis(), player.getUniqueId().toString()
        ));
    }
}
