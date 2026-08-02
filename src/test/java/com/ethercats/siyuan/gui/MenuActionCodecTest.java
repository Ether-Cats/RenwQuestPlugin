package com.ethercats.siyuan.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuActionCodecTest {
    @Test
    void normalizesDeluxeAndChineseAliases() {
        assertEquals("command:spawn", MenuActionCodec.normalize("[PLAYER] spawn"));
        assertEquals("console:give %player% diamond 1", MenuActionCodec.normalize("[console] give %player% diamond 1"));
        assertEquals("message:&aHello", MenuActionCodec.normalize("[message] &aHello"));
        assertEquals("menu:rewards", MenuActionCodec.normalize("打开菜单: rewards"));
        assertEquals("close", MenuActionCodec.normalize("关闭"));
    }

    @Test
    void preservesSupportedActionsThroughDeluxeExport() {
        List<String> actions = List.of("tell:&aHello", "console:say hi", "sound:ENTITY_PLAYER_LEVELUP-1-1", "close");
        List<String> exported = MenuActionCodec.toDeluxe(actions);
        assertEquals(List.of("[message] &aHello", "[console] say hi", "[sound] ENTITY_PLAYER_LEVELUP-1-1", "[close]"), exported);
        assertTrue(MenuActionCodec.isSupported("chat:hello world"));
    }
}
