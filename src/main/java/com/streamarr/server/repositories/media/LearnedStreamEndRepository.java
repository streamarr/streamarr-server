package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.util.List;
import java.util.UUID;

/**
 * The stream end times that job attempts learned where a container header states none (ADR 0037,
 * early ends). They are kept apart from the probe results, so a new probe never discards them.
 */
public interface LearnedStreamEndRepository {

  /**
   * Stores the learned end time while the file's stored probe outcome holds its snapshot, unless
   * one is already stored for the same stream of that snapshot: the first learned end time stays.
   * One learned for another snapshot of the file is replaced. Returns whether the end time was
   * stored.
   */
  boolean store(LearnedStreamEnd learned);

  /** Reads the end times learned for the file's streams at the snapshot. */
  List<LearnedStreamEnd> findFor(UUID mediaFileId, SourceFileSnapshot snapshot);
}
