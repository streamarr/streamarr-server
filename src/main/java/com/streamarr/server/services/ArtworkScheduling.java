package com.streamarr.server.services;

import com.streamarr.server.domain.media.ArtworkItem;

/** Schedules the artwork steps of an item on the scheduler of the item's artwork priority. */
public interface ArtworkScheduling {

  /**
   * Schedules the item's artwork steps to run now, in the caller's transaction. A pending scheduled
   * task that waits to retry runs now instead; a running one checks the item's request again when
   * it completes. Call it only while the transaction holds the item's artwork request lock.
   */
  void scheduleSteps(ArtworkItem item);
}
