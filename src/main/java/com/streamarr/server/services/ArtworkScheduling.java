package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkTarget;

/** Schedules the artwork steps of a target on the scheduler of the target's artwork priority. */
public interface ArtworkScheduling {

  /**
   * Schedules the target's artwork steps to run now, in the caller's transaction. A pending
   * scheduled task that waits to retry runs now instead; a running one checks the target's request
   * again when it completes. Call it only while the transaction holds the target's artwork request
   * lock.
   */
  void scheduleSteps(ArtworkTarget target);
}
