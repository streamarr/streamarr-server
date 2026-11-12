package com.streamarr.server.services.streaming.local;

import lombok.NonNull;

/** What the server's start did with the leftovers of earlier server processes. */
public sealed interface SegmentFolderLeftoversOutcome {

  String metricTag();

  /** The server shares its segment folder with other server processes, so it deletes nothing. */
  record NoOwnedFolder() implements SegmentFolderLeftoversOutcome {

    @Override
    public String metricTag() {
      return "no_owned_folder";
    }
  }

  /**
   * The delete ran.
   *
   * @param deleted the stream sessions' directories and unpublished uploads that it deleted
   * @param remaining those that it could not delete
   */
  record Deleted(int deleted, int remaining) implements SegmentFolderLeftoversOutcome {

    @Override
    public String metricTag() {
      return "deleted";
    }
  }

  /** The server deleted nothing in the folder, and every leftover there remains. */
  record Refused(@NonNull SegmentFolderRefusal refusal) implements SegmentFolderLeftoversOutcome {

    @Override
    public String metricTag() {
      return refusal.metricTag();
    }
  }
}
