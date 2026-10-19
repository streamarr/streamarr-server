package com.streamarr.server.repositories.streaming;

import com.streamarr.server.services.pagination.MediaOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ContinueWatchingRepository {
  List<UUID> findCollectableIds(UUID profileId, int limit);

  Map<UUID, MediaOrder> findMatchingPositions(UUID profileId, Set<UUID> itemIds);
}
