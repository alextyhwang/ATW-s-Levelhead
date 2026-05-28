package com.atw.levelhead.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BedwarsFetchResult {
    private final Map<UUID, LevelTag> tags;
    private final Map<UUID, BedwarsPlayerStats> stats;

    public BedwarsFetchResult(Map<UUID, LevelTag> tags, Map<UUID, BedwarsPlayerStats> stats) {
        this.tags = Collections.unmodifiableMap(new HashMap<>(tags));
        this.stats = Collections.unmodifiableMap(new HashMap<>(stats));
    }

    public Map<UUID, LevelTag> getTags() {
        return tags;
    }

    public Map<UUID, BedwarsPlayerStats> getStats() {
        return stats;
    }
}
