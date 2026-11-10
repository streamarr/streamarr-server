package com.streamarr.server.services.streaming;

import java.nio.file.Path;

/**
 * A stored segment that is one whole file. While the file exists at its path it holds exactly the
 * segment's bytes, so a later open of the path reads the same segment or finds no file.
 */
public interface StoredSegmentFile extends StoredSegment {

  /** The absolute path of the segment's file. */
  Path path();
}
