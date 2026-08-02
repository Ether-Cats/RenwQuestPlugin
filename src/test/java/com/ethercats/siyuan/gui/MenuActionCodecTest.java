package com.ethercats.siyuan.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuActionCodecTest {
    @Test
    void normalizesDeluxeAndChineseAliases() {
        assertEquals("command:spawn", MenuActionCodec.normalize("[PLAYER] spawn"));
        assertEquals("console:give %player% diamond 1", MenuActionCodec.normalize("[console] give %player% diamond 1"));
        assertEquals("message:&aHello", MenuActionCodec.normalize("[message] &aHello"));
        assertEquals("menu:rewards", MenuActionCodec.normalize("打开菜单: rewards"));
        assertEquals("close", MenuActionCodec.normalize("关闭"));
        assertEquals("catcher:feedback|end=tell:&aDone", MenuActionCodec.normalize("聊天输入: feedback|end=tell:&aDone"));
        assertEquals("book:feedback|end=tell:&aDone", MenuActionCodec.normalize("书本: feedback|end=tell:&aDone"));
    }

    @Test
    void preservesSupportedActionsThroughDeluxeExport() {
        List<String> actions = List.of("tell:&aHello", "console:say hi", "sound:ENTITY_PLAYER_LEVELUP-1-1", "close");
        List<String> exported = MenuActionCodec.toDeluxe(actions);
        assertEquals(List.of("[message] &aHello", "[console] say hi", "[sound] ENTITY_PLAYER_LEVELUP-1-1", "[close]"), exported);
        assertTrue(MenuActionCodec.isSupported("chat:hello world"));
        assertTrue(MenuActionCodec.isSupported("book:feedback|end=tell:&aSaved"));
        assertTrue(MenuActionCodec.isSupported("book:"));
    }

    @Test
    void detectsNestedConsoleActions() {
        assertTrue(MenuActionCodec.hasConsoleAction("console:say hello"));
        assertTrue(MenuActionCodec.hasConsoleAction("book:feedback|end=console:feedback save %player% %book_input%"));
        assertTrue(MenuActionCodec.hasConsoleAction("聊天输入:feedback|cancel=控制台命令:say cancelled"));
        assertFalse(MenuActionCodec.hasConsoleAction("catcher:feedback|end=tell:&aSaved"));
    }
}
