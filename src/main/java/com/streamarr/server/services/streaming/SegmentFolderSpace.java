package com.streamarr.server.services.streaming;

/** The free space of the file system that holds the segment folder. */
public interface SegmentFolderSpace {

  /**
   * The share of the file system that is free for the server to write, from 0 to 1.
   *
   * @throws java.io.UncheckedIOException when the file system cannot be read
   */
  double freeFraction();
}
