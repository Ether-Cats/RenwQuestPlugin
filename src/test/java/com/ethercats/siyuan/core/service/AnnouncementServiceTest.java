package com.ethercats.siyuan.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnnouncementServiceTest {
    @Test
    void clampsAnnouncementIntervalsToSaneBounds() {
        assertEquals(10, AnnouncementService.normalizeIntervalSeconds(0));
        assertEquals(300, AnnouncementService.normalizeIntervalSeconds(300));
        assertEquals(86_400, AnnouncementService.normalizeIntervalSeconds(100_000));
    }
}
