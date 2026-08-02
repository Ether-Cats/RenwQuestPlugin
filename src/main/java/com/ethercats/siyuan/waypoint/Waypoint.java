package com.ethercats.siyuan.waypoint;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public class Waypoint {
    private final int slot;
    private final String world;
    private final double x, y, z;
    private final float yaw, pitch;
    private String icon;
    private String name;
    
    public Waypoint(int slot, String world, double x, double y, double z, 
                   float yaw, float pitch, String icon, String name) {
        this.slot = slot;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.icon = icon;
        this.name = name;
    }
    
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }
    
    public static Waypoint fromLocation(Location loc, int slot, String icon, String name) {
        return new Waypoint(slot, loc.getWorld().getName(), 
                           loc.getX(), loc.getY(), loc.getZ(),
                           loc.getYaw(), loc.getPitch(), icon, name);
    }
    
    // Getters & Setters
    public int getSlot() { return slot; }
    public String getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

