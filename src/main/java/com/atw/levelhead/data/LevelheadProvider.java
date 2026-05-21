package com.atw.levelhead.data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LevelheadProvider {
    boolean isReady();

    String getStatus();

    void reset();

    Map<UUID, LevelTag> fetch(List<UUID> uuids) throws Exception;
}
