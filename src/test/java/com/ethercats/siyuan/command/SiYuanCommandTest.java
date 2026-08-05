package com.ethercats.siyuan.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiYuanCommandTest {
    @Test
    void formatsStatusValuesWithoutLeakingRawBytes() {
        assertEquals("1.0 KB", SiYuanCommand.formatBytes(1024));
        assertEquals("§a20.00", SiYuanCommand.formatTps(20.4D));
        assertEquals("§c0.00", SiYuanCommand.formatTps(-1D));
    }
}
