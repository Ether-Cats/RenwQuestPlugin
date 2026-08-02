package com.ethercats.siyuan.quest.listener;

import com.ethercats.siyuan.SiYuanPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuestListener implements Listener {
    
    private final SiYuanPlugin plugin;
    private final Set<UUID> airborne = ConcurrentHashMap.newKeySet();
    
    public QuestListener(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        plugin.getQuestManager().addProgress(e.getPlayer(), "BLOCK_BREAK", 
                                            e.getBlock().getType().name(), 1);
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() instanceof Player killer) {
            plugin.getQuestManager().addProgress(killer, "ENTITY_KILL", 
                                                e.getEntityType().name(), 1);
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            int crafted = e.getCurrentItem() == null ? 1 : Math.max(1, e.getCurrentItem().getAmount());
            plugin.getQuestManager().addProgress(p, "ITEM_CRAFT", 
                                                e.getRecipe().getResult().getType().name(), 
                                                crafted);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        plugin.getQuestManager().addProgress(e.getPlayer(), "ITEM_CONSUME", 
                                            e.getItem().getType().name(), 1);
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        UUID uuid = e.getPlayer().getUniqueId();
        if (e.getPlayer().isOnGround()) {
            airborne.remove(uuid);
        } else if (e.getPlayer().getVelocity().getY() > 0.1 && airborne.add(uuid)) {
            plugin.getQuestManager().addProgress(e.getPlayer(), "PLAYER_JUMP", "ANY", 1);
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player damager) {
            plugin.getQuestManager().addProgress(damager, "DAMAGE_DEALT", 
                                                e.getEntityType().name(), Math.max(1, (int) Math.ceil(e.getDamage())));
        }
        if (e.getEntity() instanceof Player victim) {
            plugin.getQuestManager().addProgress(victim, "DAMAGE_TAKEN", 
                                                e.getDamager().getType().name(), Math.max(1, (int) Math.ceil(e.getDamage())));
        }
    }
}
