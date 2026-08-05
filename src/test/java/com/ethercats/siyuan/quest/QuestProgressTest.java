package com.ethercats.siyuan.quest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestProgressTest {
    @Test
    void requiresEveryObjectiveBeforeCompletion() {
        QuestConfig config = new QuestConfig(
            "test", "Test", "", QuestType.DAILY, 50, 1,
            List.of(
                new QuestConfig.Objective("BLOCK_BREAK", "STONE", 3),
                new QuestConfig.Objective("ENTITY_KILL", "ZOMBIE", 2)
            )
        );
        QuestProgress progress = new QuestProgress(
            "test", QuestType.DAILY, new HashMap<>(), QuestStatus.IN_PROGRESS,
            System.currentTimeMillis(), null, "2026-08-01"
        );

        progress.addProgress(0, 3);
        assertFalse(progress.isCompleted(config));
        progress.addProgress(1, 2);
        assertTrue(progress.isCompleted(config));
    }
}
