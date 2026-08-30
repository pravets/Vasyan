package ru.pravets.vasyan.llm.resilience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline fallback must preserve the requested gathered resource. */
class LLMFallbackHandlerTest {

    @Test
    void gatherCoalFallbackKeepsCoalResourceAndQuantity() {
        var handler = new LLMFallbackHandler();

        String content = handler.generateFallback(
            "=== YOUR SITUATION ===\nPosition: [144, 201, 0]\nCURRENT REQUEST: gather 1 coal", null)
            .getContent();

        assertTrue(content.contains("\"action\":\"gather\""));
        assertTrue(content.contains("\"resource\":\"coal\""));
        assertTrue(content.contains("\"quantity\":1"));
    }

    @Test
    void ironRequestWithCoalInSituationDoesNotFallbackToCoal() {
        var handler = new LLMFallbackHandler();

        String content = handler.generateFallback(
            "=== YOUR SITUATION ===\nPosition: [144, 201, 0]\nNearby blocks: coal_ore\n"
                + "CURRENT REQUEST: gather 10 iron", null)
            .getContent();

        assertFalse(content.contains("\"resource\":\"coal\""),
            "coal mention in the situation block must not override the current iron request");
    }
}
