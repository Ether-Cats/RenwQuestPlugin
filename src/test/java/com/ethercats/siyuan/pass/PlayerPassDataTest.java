package com.ethercats.siyuan.pass;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPassDataTest {
    @Test
    void appliesMultipleLevelUpsAndCarriesExperience() {
        PassConfig config = new PassConfig("test", "Test", 5, 100, 2.0);
        PlayerPassData data = data(TierType.FREE);

        assertEquals(2, data.addExp(350, config));
        assertEquals(3, data.getLevel());
        assertEquals(50, data.getExperience());
        assertEquals(350, data.getTotalExpEarned());
    }

    @Test
    void capsAtMaximumLevel() {
        PassConfig config = new PassConfig("test", "Test", 3, 100, 1.0);
        PlayerPassData data = data(TierType.FREE);

        assertEquals(2, data.addExp(10_000, config));
        assertEquals(3, data.getLevel());
        assertEquals(0, data.getExperience());
    }

    @Test
    void enforcesTierAndDuplicateClaimRules() {
        PlayerPassData data = data(TierType.PREMIUM);
        data.setLevel(10);

        assertTrue(data.canClaimReward(10, TierType.FREE));
        assertTrue(data.canClaimReward(10, TierType.PREMIUM));
        assertFalse(data.canClaimReward(10, TierType.VIP));
        data.claimReward(10, TierType.PREMIUM);
        assertFalse(data.canClaimReward(10, TierType.PREMIUM));
    }

    private PlayerPassData data(TierType tier) {
        return new PlayerPassData(UUID.randomUUID(), "season", "test", tier, 1, 0, 0, new HashSet<>());
    }
}
