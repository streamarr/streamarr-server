package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileContainerInfoRepositoryCustom {

  /**
   * Replaces the stored outcome and its stream rows in one transaction. Returns {@code false}
   * without writing when the media file no longer exists or when a newer probe version already
   * holds a result for the same source snapshot, or when the latest requested inputs differ.
   */
  boolean publish(ProbePublication publication);

  /**
   * Records desired inputs and invalidates a changed-source outcome under the media file lock.
   * Returns false when the media file no longer exists.
   */
  boolean recordProbeRequest(UUID mediaFileId, ProbeInputs inputs);

  /** Reads desired inputs under the media file lock. Requires the caller's transaction. */
  Optional<ProbeInputs> lockProbeInputs(UUID mediaFileId);
}
