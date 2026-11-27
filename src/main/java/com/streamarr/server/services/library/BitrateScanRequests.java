package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.BitrateScanPriority;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;

/** Schedules the durable bitrate scan of a media file (ADR 0054). */
@FunctionalInterface
public interface BitrateScanRequests {

  /**
   * Joins the caller's transaction, so the scan is scheduled only if that transaction commits. A
   * file already scheduled keeps its scan.
   */
  void request(BitrateScanTaskRequest request, BitrateScanPriority priority);
}
