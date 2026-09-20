package com.corecc.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {
    @TempDir Path tempDir;

    @Test
    void addSearchListAndDeleteUseThePersistentStore() {
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"), "test-workspace");
        MemoryEntry added = store.add("Prefer focused unit tests", List.of("testing"),
            "testing-style", null, "feedback", null);

        assertEquals(added.getId(), store.listRecent(10).get(0).getId());
        assertEquals(added.getId(), store.search("focused", 10, false).get(0).getId());
        assertTrue(store.delete(added.getId().substring(0, 4)));
        assertTrue(store.listRecent(10).isEmpty());
    }
}
