package com.streamarr.server.repositories.streaming;

import java.util.List;
import java.util.UUID;

public interface ContinueWatchingRepository {

  List<UUID> findCollectableIds(UUID userId, int limit);
}
