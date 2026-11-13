package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.task.BitrateScanPriority;
import java.util.UUID;

@FunctionalInterface
public interface BitrateScanPriorityRepository {

  /**
   * Raises the claim priority of the file's bitrate scan and keeps its execution time, inputs and
   * failure history. A running scan keeps the raised priority for its remaining chunks. Returns
   * {@code false} without writing when the file has no bitrate scan or its scan already has at
   * least that priority.
   */
  boolean tryRaisePriority(UUID mediaFileId, BitrateScanPriority priority);
}
