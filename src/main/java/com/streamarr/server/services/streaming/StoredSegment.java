package com.streamarr.server.services.streaming;

import java.io.InputStream;

/**
 * A stored segment opened for reading. Its bytes stay readable until it is closed, even when its
 * stream session is deleted meanwhile. The caller closes it when its answer ends.
 */
public interface StoredSegment extends AutoCloseable {

  /** The segment's length in bytes. */
  long length();

  /** A stream over the segment from its first byte. Each stream keeps its own position. */
  InputStream newInputStream();

  /** Releases the opened segment. Closing it again does nothing. */
  @Override
  void close();
}
