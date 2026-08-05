package com.ethercats.siyuan.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityAuditServiceTest {
    @Test
    void clampsAuditResourceLimits() {
        assertEquals(100, ActivityAuditService.normalizeQueueSize(1));
        assertEquals(10_000, ActivityAuditService.normalizeQueueSize(100_001));
        assertEquals(1, ActivityAuditService.normalizeFlushSeconds(0));
        assertEquals(60, ActivityAuditService.normalizeFlushSeconds(1_000));
    }
}
