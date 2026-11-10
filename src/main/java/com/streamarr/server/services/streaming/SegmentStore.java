package com.streamarr.server.services.streaming;

import java.util.Optional;
import java.util.UUID;

public interface SegmentStore {

  /**
   * Opens a published segment, or answers empty when the stream session stores no segment under the
   * name. The caller closes the opened segment.
   */
  Optional<StoredSegment> openSegment(UUID sessionId, String segmentName);

  boolean segmentExists(UUID sessionId, String segmentName);

  PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data);

  default SegmentPublication storeSegment(UUID sessionId, String segmentName, byte[] data) {
    try (var prepared = prepareSegment(sessionId, segmentName, data)) {
      return prepared.publish();
    }
  }

  void deleteSession(UUID sessionId);

  interface PreparedSegment extends AutoCloseable {

    /**
     * Makes the prepared bytes readable under the segment's name. A variant's initialization
     * segment is stored once: its first publication is atomic, identical bytes publish again
     * without change, and different bytes are refused while the stored segment stays untouched.
     */
    SegmentPublication publish();

    @Override
    void close();
  }
}
