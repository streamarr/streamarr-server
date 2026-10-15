package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;

/** Schedules the durable keyframe packet scan of a media file whose stored outcome needs one. */
@FunctionalInterface
public interface KeyframePacketScanRequests {

  /** Joins the caller's transaction, so the scan is scheduled only if that transaction commits. */
  void request(KeyframePacketScanTaskRequest request);
}
