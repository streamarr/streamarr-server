package com.streamarr.server.repositories.streaming;

import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ContinueWatchingRepository {
  List<Row<UUID>> rowsBeyond(UUID profileId, Seek seek);

  Map<UUID, Instant> findMatchingItems(UUID profileId, Set<UUID> itemIds);
}
