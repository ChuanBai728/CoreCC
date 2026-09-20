package com.corecc.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LLMModelTest {
    @Test
    void modelCanBeChangedWithoutRecreatingTheClient() {
        LLM llm = new LLM("first", "key", "http://localhost", Map.of());
        llm.setModel(" second ");
        assertEquals("second", llm.getModel());
        assertThrows(IllegalArgumentException.class, () -> llm.setModel(" "));
    }
}
