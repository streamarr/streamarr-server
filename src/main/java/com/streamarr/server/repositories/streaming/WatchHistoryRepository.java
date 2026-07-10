package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.WatchHistory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchHistoryRepository
    extends JpaRepository<WatchHistory, UUID>, WatchHistoryRepositoryCustom {

  Optional<WatchHistory> findFirstByProfileIdAndCollectableIdOrderByWatchedAtDesc(
      UUID profileId, UUID collectableId);

  List<WatchHistory> findByProfileIdAndCollectableIdIn(
      UUID profileId, Collection<UUID> collectableIds);
}
