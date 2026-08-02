package com.ethercats.siyuan.pass;

public enum TierType {
    FREE, PREMIUM, VIP;
    
    public static TierType fromString(String s) {
        if (s == null) return FREE;
        try { return valueOf(s.toUpperCase()); }
        catch (Exception e) { return FREE; }
    }
}

