package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFileStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MediaFileRepositoryCustom {

  List<UUID> findMediaFileIdsByMediaIds(Collection<UUID> mediaIds);

  Optional<UUID> findMediaIdByMediaFileId(UUID mediaFileId);

  Set<UUID> findDistinctMediaIdsByMediaIdIn(Collection<UUID> mediaIds);

  /**
   * Stores the failure status and reason unless the file has been matched since; a matched file is
   * never reprocessed, so any failure that arrives after its match is stale. Returns {@code false}
   * without writing when the file is matched or missing.
   */
  boolean tryMarkMatchingFailed(UUID mediaFileId, MatchingFailure failure);

  /** Counts the listed media files that still exist by their matching status. */
  Map<MediaFileStatus, Long> countStatuses(Collection<UUID> mediaFileIds);
}
