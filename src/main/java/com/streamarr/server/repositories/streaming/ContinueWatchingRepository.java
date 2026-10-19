package com.streamarr.server.repositories.streaming;

import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ContinueWatchingRepository {
  List<PageItem<UUID>> findItems(UUID profileId, MediaPaginationOptions options);

  Map<UUID, Instant> findMatchingItems(UUID profileId, Set<UUID> itemIds);
}
