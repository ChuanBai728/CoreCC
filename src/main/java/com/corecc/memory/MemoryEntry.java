package com.corecc.memory;

import java.util.*;

/**
 * 一条持久化的长期记忆。
 *
 * 对应 Python 版的 corecc/memory.py 中的 MemoryEntry dataclass。
 */
public class MemoryEntry {
    private String id;
    private String name;
    private String description;
    private String type;  // user, feedback, project, reference
    private String content;
    private String scope;  // private, team
    private List<String> tags;
    private String createdAt;
    private String updatedAt;
    private int hits;
    private String filename;

    public MemoryEntry() {
        this.tags = new ArrayList<>();
        this.hits = 0;
    }

    public MemoryEntry(String id, String name, String description, String type,
                       String content, String scope, List<String> tags,
                       String createdAt, String updatedAt, int hits, String filename) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.content = content;
        this.scope = scope;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.hits = hits;
        this.filename = filename;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public int getHits() { return hits; }
    public void setHits(int hits) { this.hits = hits; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
}
