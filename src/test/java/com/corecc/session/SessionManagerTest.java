package com.corecc.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @Test
    void normalizedSessionIdsAreSafeAndBounded() {
        String normalized = SessionManager.normalizeSessionId("../../" + "a".repeat(200));
        assertEquals(100, normalized.length());
        assertFalse(normalized.contains("/"));
        assertFalse(normalized.contains("\\"));
    }
}
