package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbePublication;
import java.util.UUID;

public interface MediaFileContainerInfoRepositoryCustom {

  /**
   * Replaces the stored outcome and its stream rows in one transaction. Returns {@code false}
   * without writing when the media file no longer exists or when a newer probe version already
   * holds a result for the same source snapshot.
   */
  boolean publish(ProbePublication publication);

  /** Deletes the stored outcome, and with it the stream rows, when the source has changed. */
  void invalidateOutcomeUnlessSnapshotMatches(UUID mediaFileId, SourceFileSnapshot snapshot);
}
