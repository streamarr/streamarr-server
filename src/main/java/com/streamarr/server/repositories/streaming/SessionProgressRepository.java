package com.streamarr.server.repositories.streaming;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionProgressRepository
    extends JpaRepository<SessionProgress, UUID>, SessionProgressRepositoryCustom {

  Optional<SessionProgress> findBySessionId(UUID sessionId);

  List<SessionProgress> findByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds);

  void deleteByUserIdAndMediaFileIdIn(UUID userId, Collection<UUID> mediaFileIds);

  @Query(
      """
      SELECT sp FROM SessionProgress sp
      WHERE sp.userId = :userId AND sp.mediaFileId = :mediaFileId
      ORDER BY sp.lastModifiedOn DESC
      LIMIT 1
      """)
  Optional<SessionProgress> findMostRecentByUserIdAndMediaFileId(
      @Param("userId") UUID userId, @Param("mediaFileId") UUID mediaFileId);
}
