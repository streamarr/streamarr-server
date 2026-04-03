package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionProgressRepository
    extends JpaRepository<SessionProgress, UUID>, SessionProgressRepositoryCustom {

  Optional<SessionProgress> findByUserIdAndMediaFileId(UUID userId, UUID mediaFileId);

  List<SessionProgress> findByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds);

  void deleteByUserIdAndMediaFileId(UUID userId, UUID mediaFileId);

  void deleteByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds);
}
