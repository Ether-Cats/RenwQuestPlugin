package com.ethercats.siyuan.season;

import java.sql.ResultSet;
import java.sql.SQLException;

public class Season {
    private final String id;
    private final String name;
    private final long startTime;
    private Long endTime;
    private boolean active;
    
    public Season(String id, String name, long startTime, Long endTime, boolean active) {
        this.id = id;
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
        this.active = active;
    }
    
    public boolean isActive() {
        return active && (endTime == null || System.currentTimeMillis() < endTime);
    }
    
    public static Season fromResultSet(ResultSet rs) throws SQLException {
        return new Season(
            rs.getString("id"),
            rs.getString("name"),
            rs.getLong("start_time"),
            rs.getObject("end_time", Long.class),
            rs.getBoolean("active")
        );
    }
    
    // Getters & Setters
    public String getId() { return id; }
    public String getName() { return name; }
    public long getStartTime() { return startTime; }
    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }
    public boolean isActiveFlag() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

