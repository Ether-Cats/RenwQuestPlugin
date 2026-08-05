package com.ethercats.siyuan.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MenuEditorManagerTest {
    @Test
    void updatesClickActionListsWithoutDiscardingExistingActions() {
        List<String> initial = List.of("command:gc quest daily");
        List<String> appended = MenuEditorManager.updateActions(initial, "add", "tell:&a任务已打开");

        assertEquals(List.of("command:gc quest daily", "tell:&a任务已打开"), appended);
        assertEquals(List.of("tell:&a任务已打开"), MenuEditorManager.updateActions(appended, "remove", "0"));
        assertEquals(List.of(), MenuEditorManager.updateActions(appended, "clear", ""));
        assertEquals(List.of("menu:main"), MenuEditorManager.updateActions(appended, "set", "打开菜单: main"));
    }

    @Test
    void rejectsInvalidActionOperationsAndIndexes() {
        assertThrows(IllegalArgumentException.class,
            () -> MenuEditorManager.updateActions(List.of(), "append", "command:gc"));
        assertThrows(IllegalArgumentException.class,
            () -> MenuEditorManager.updateActions(List.of("close"), "remove", "1"));
    }
}
