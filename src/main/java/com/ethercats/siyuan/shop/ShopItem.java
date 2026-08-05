package com.ethercats.siyuan.shop;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

public class ShopItem {
    private final String id;
    private final UUID sellerUuid;
    private final String sellerName;
    private final String itemBase64;
    private final String itemName;
    private int amount;
    private final double pricePerUnit;
    private final long listedAt;
    
    public ShopItem(String id, UUID sellerUuid, String sellerName, String itemBase64, 
                   String itemName, int amount, double pricePerUnit, long listedAt) {
        this.id = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemBase64 = itemBase64;
        this.itemName = itemName;
        this.amount = amount;
        this.pricePerUnit = pricePerUnit;
        this.listedAt = listedAt;
    }
    
    public ItemStack toItemStack() {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(Base64Coder.decodeLines(itemBase64));
            BukkitObjectInputStream bois = new BukkitObjectInputStream(bis);
            ItemStack item = (ItemStack) bois.readObject();
            bois.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }
    
    public static String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BukkitObjectOutputStream boos = new BukkitObjectOutputStream(bos);
            boos.writeObject(item);
            boos.close();
            return Base64Coder.encodeLines(bos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
    
    public double getTotalPrice() { return amount * pricePerUnit; }
    
    // Getters
    public String getId() { return id; }
    public UUID getSellerUuid() { return sellerUuid; }
    public String getSellerName() { return sellerName; }
    public String getItemBase64() { return itemBase64; }
    public String getItemName() { return itemName; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = Math.max(0, amount); }
    public double getPricePerUnit() { return pricePerUnit; }
    public long getListedAt() { return listedAt; }
}
