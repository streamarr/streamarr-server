package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/** Reads stored segments whole, and builds ones held in memory. */
public final class StoredSegments {

  private StoredSegments() {}

  public static StoredSegment inMemory(byte[] data) {
    return new StoredSegment() {
      @Override
      public long length() {
        return data.length;
      }

      @Override
      public InputStream newInputStream() {
        return new ByteArrayInputStream(data);
      }

      @Override
      public void close() {
        // The bytes are on the heap, so there is nothing to release.
      }
    };
  }

  public static byte[] bytes(SegmentStore store, UUID sessionId, String segmentName) {
    try (var segment = store.openSegment(sessionId, segmentName).orElseThrow()) {
      return bytes(segment);
    }
  }

  public static byte[] bytes(SegmentDelivery delivery) {
    if (!(delivery instanceof SegmentDelivery.Ready(var segment))) {
      throw new AssertionError("Expected a ready delivery but got " + delivery);
    }

    try (segment) {
      return bytes(segment);
    }
  }

  private static byte[] bytes(StoredSegment segment) {
    try (var stream = segment.newInputStream()) {
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
