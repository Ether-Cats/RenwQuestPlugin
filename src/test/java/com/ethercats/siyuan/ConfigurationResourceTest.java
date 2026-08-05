package com.ethercats.siyuan;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigurationResourceTest {
    @Test
    void shipsValidDefaultsForIntegratedOperationalServices() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
            Path.of("src", "main", "resources", "config.yml").toFile());

        assertNotNull(config.getConfigurationSection("menu-command-bindings"));
        assertNotNull(config.getConfigurationSection("announcements"));
        assertNotNull(config.getConfigurationSection("activity-audit"));
        assertNotNull(config.getConfigurationSection("ai-assistant"));
        assertFalse(config.getBoolean("announcements.enabled"));
        assertFalse(config.getBoolean("activity-audit.enabled"));
        assertFalse(config.getBoolean("ai-assistant.enabled"));
    }
}
