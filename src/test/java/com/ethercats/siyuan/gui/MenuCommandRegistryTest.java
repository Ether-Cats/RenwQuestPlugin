package com.ethercats.siyuan.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuCommandRegistryTest {
    @Test
    void normalizesOnlySafeCommandLabels() {
        assertEquals("menu-open", MenuCommandRegistry.normalizeCommand(" /Menu-Open "));
        assertEquals("siyuan:daily", MenuCommandRegistry.normalizeCommand("siyuan:daily"));
        assertNull(MenuCommandRegistry.normalizeCommand("menu open"));
        assertNull(MenuCommandRegistry.normalizeCommand("../op"));
        assertNull(MenuCommandRegistry.normalizeCommand("/"));
    }

    @Test
    void removesOnlyTheRequestedMenuOwnersCommands() {
        MenuCommandStore<String> store = new MenuCommandStore<>();
        store.add("main", "main", "main-command");
        store.add("daily", "daily", "daily-command");

        assertEquals(List.of("main-command"), List.copyOf(store.removeOwner("main")));
        assertEquals(List.of("daily"), List.copyOf(store.keys()));
        assertEquals(List.of("daily-command"), List.copyOf(store.removeAll()));
    }
}
