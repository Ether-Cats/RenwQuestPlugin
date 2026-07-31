package top.etca.renw;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.entity.Player;

public class QuestListener implements Listener {
    private final renw plugin;

    public QuestListener(renw plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String blockName = event.getBlock().getType().name();
        plugin.getQuestManager().addProgress(player, "BLOCK_BREAK", blockName, 1);
    }

    @EventHandler
    public void onEntityKill(EntityDeathEvent event) {
        if (event.getEntity().getKiller() != null) {
            Player player = event.getEntity().getKiller();
            String entityName = event.getEntityType().name();
            plugin.getQuestManager().addProgress(player, "ENTITY_KILL", entityName, 1);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            String itemName = event.getRecipe().getResult().getType().name();
            int amount = event.getRecipe().getResult().getAmount();
            plugin.getQuestManager().addProgress(player, "ITEM_CRAFT", itemName, amount);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        String itemName = event.getItem().getType().name();
        plugin.getQuestManager().addProgress(player, "ITEM_CONSUME", itemName, 1);
    }
}