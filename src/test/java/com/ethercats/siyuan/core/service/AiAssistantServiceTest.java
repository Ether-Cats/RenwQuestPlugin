package com.ethercats.siyuan.core.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAssistantServiceTest {
    @Test
    void normalizesOpenAiCompatibleEndpointsWithoutAllowingInsecureHttpByDefault() {
        assertEquals(URI.create("https://api.example.test/v1/chat/completions"),
            AiAssistantService.buildEndpoint("https://api.example.test/v1/", false));
        assertEquals(URI.create("http://127.0.0.1:11434/v1/chat/completions"),
            AiAssistantService.buildEndpoint("http://127.0.0.1:11434/v1", true));
        assertThrows(IllegalArgumentException.class,
            () -> AiAssistantService.buildEndpoint("http://api.example.test/v1", false));
        assertThrows(IllegalArgumentException.class,
            () -> AiAssistantService.buildEndpoint("https://token@api.example.test/v1", false));
    }

    @Test
    void extractsAndBoundsTextResponsesForChat() {
        String response = """
            {"choices":[{"message":{"content":"first line\\nsecond line"}}]}
            """;
        assertEquals("first line\nsecond line", AiAssistantService.extractContent(response));
        assertEquals(List.of("first line", "second line"),
            AiAssistantService.splitForChat("first line\nsecond line", 40));
    }
}
