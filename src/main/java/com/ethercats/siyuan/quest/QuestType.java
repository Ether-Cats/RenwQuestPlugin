package com.ethercats.siyuan.quest;

public enum QuestType {
    DAILY, WEEKLY, SEASONAL, STORY, CHALLENGE;
    
    public static QuestType fromString(String s) {
        if (s == null) return DAILY;
        try { return valueOf(s.toUpperCase()); }
        catch (Exception e) { return DAILY; }
    }
}

