package com.ethercats.siyuan.shop;

/** Read-only projection used by the shop history GUI. */
public record ShopTransaction(
    String buyerName,
    String sellerName,
    String itemName,
    int amount,
    double unitPrice,
    double totalPrice,
    long timestamp
) { }
