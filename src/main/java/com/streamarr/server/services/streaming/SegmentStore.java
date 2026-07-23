package com.streamarr.server.services.streaming;

import java.util.UUID;

public interface SegmentStore {

  byte[] readSegment(UUID sessionId, String segmentName);

  boolean segmentExists(UUID sessionId, String segmentName);

  PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data);

  default void storeSegment(UUID sessionId, String segmentName, byte[] data) {
    try (var prepared = prepareSegment(sessionId, segmentName, data)) {
      prepared.publish();
    }
  }

  void deleteSession(UUID sessionId);

  interface PreparedSegment extends AutoCloseable {

    void publish();

    @Override
    void close();
  }
}
