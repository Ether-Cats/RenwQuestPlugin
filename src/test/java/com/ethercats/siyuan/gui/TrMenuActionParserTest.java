package com.ethercats.siyuan.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrMenuActionParserTest {
    @Test
    void parsesScalarAndClickSpecificActions() throws Exception {
        YamlConfiguration scalar = new YamlConfiguration();
        scalar.loadFromString("actions: command:gc quest daily");
        TrMenuActionParser.ClickActions scalarActions = TrMenuActionParser.parse(scalar.get("actions"));

        assertEquals(List.of("command:gc quest daily"), scalarActions.all());
        assertEquals(List.of(), scalarActions.left());

        YamlConfiguration clickSpecific = new YamlConfiguration();
        clickSpecific.loadFromString("""
            actions:
              left: "[message] &aLeft"
              right:
                - "[player] gc quest weekly"
            """);
        TrMenuActionParser.ClickActions clickActions = TrMenuActionParser.parse(clickSpecific.get("actions"));

        assertEquals(List.of("message:&aLeft"), clickActions.left());
        assertEquals(List.of("command:gc quest weekly"), clickActions.right());
    }

    @Test
    void parsesStructuredCatcherActions() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
            actions:
              all:
                catcher:
                  feedback:
                    start: "tell:&e请输入反馈"
                    cancel:
                      - "tell:&c已取消"
                    end:
                      actions:
                        - "console:feedback save %player% %trmenu_meta_input-feedback%"
            """);

        TrMenuActionParser.ClickActions actions = TrMenuActionParser.parse(config.get("actions"));

        assertEquals(List.of(
            "catcher:feedback|start=tell:&e请输入反馈|cancel=tell:&c已取消|end=console:feedback save %player% %trmenu_meta_input-feedback%"
        ), actions.all());
    }
}
