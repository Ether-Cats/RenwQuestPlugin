package com.ethercats.siyuan.integration;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.pass.PlayerPassData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PlaceholderHook extends PlaceholderExpansion {
    
    private final SiYuanPlugin plugin;
    
    public PlaceholderHook(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public @NotNull String getIdentifier() {
        return "siyuan";
    }
    
    @Override
    public @NotNull String getAuthor() {
        return "EtherCats";
    }
    
    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }
    
    @Override
    public boolean persist() {
        return true;
    }
    
    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        
        PlayerPassData data = plugin.getPassManager().getPlayerData(player.getUniqueId());
        if (data == null) return "";
        
        return switch (params.toLowerCase()) {
            case "level" -> String.valueOf(data.getLevel());
            case "exp", "experience" -> String.valueOf(data.getExperience());
            case "total_exp" -> String.valueOf(data.getTotalExpEarned());
            case "max_level" -> String.valueOf(plugin.getPassManager().getPassConfig() == null ? 0 : plugin.getPassManager().getPassConfig().getMaxLevel());
            case "next_exp" -> String.valueOf(plugin.getPassManager().getPassConfig() == null ? 0 : plugin.getPassManager().getPassConfig().getExpForLevel(data.getLevel() + 1));
            case "tier" -> data.getTier().name();
            case "tier_display" -> switch (data.getTier()) {
                case FREE -> "§7免费";
                case PREMIUM -> "§6高级";
                case VIP -> "§5至尊";
            };
            case "season" -> {
                var season = plugin.getSeasonManager().getActiveSeason();
                yield season != null ? season.getName() : "无赛季";
            }
            case "shop_listings" -> String.valueOf(plugin.getShopManager().getListings().size());
            case "waypoints" -> String.valueOf(plugin.getWaypointManager().getWaypoints(player.getUniqueId()).size());
            default -> null;
        };
    }
}
