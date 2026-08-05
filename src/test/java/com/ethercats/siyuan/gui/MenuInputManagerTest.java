package com.ethercats.siyuan.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuInputManagerTest {
    @Test
    void replacesTrMenuAndExistingInputPlaceholders() {
        String action = "console:feedback save {meta:input} %trmenu_meta_input% %trmenu_meta_input-feedback% %book_input%";

        assertEquals(
            "console:feedback save hello world hello world hello world hello world",
            MenuInputManager.replaceInput(action, " hello\nworld ", "feedback")
        );
    }
}
