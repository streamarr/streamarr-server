package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.WatchProgress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchProgressRepository
    extends JpaRepository<WatchProgress, UUID>, WatchProgressRepositoryCustom {

  Optional<WatchProgress> findByUserIdAndMediaFileId(UUID userId, UUID mediaFileId);

  List<WatchProgress> findByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds);

  void deleteByUserIdAndMediaFileId(UUID userId, UUID mediaFileId);
}
