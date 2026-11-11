package com.streamarr.server.fakes;

import com.streamarr.server.fixtures.StoredSegments;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSegmentStore implements SegmentStore {

  private final Map<UUID, Map<String, byte[]>> sessions = new ConcurrentHashMap<>();
  private final Set<StoredSegment> openSegments = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
    return Optional.ofNullable(sessions.getOrDefault(sessionId, Map.of()).get(segmentName))
        .map(this::opened);
  }

  /** The segments that were opened and not yet closed. */
  public int openSegmentCount() {
    return openSegments.size();
  }

  private StoredSegment opened(byte[] data) {
    var segment = StoredSegments.inMemory(data);
    var opened =
        new StoredSegment() {
          @Override
          public long length() {
            return segment.length();
          }

          @Override
          public InputStream newInputStream() {
            return segment.newInputStream();
          }

          @Override
          public void close() {
            openSegments.remove(this);
          }
        };
    openSegments.add(opened);
    return opened;
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return sessions.getOrDefault(sessionId, Map.of()).containsKey(segmentName);
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
    var appended = new ByteArrayOutputStream();
    return new PreparedSegment() {
      @Override
      public void append(ByteBuffer bytes) {
        var chunk = new byte[bytes.remaining()];
        bytes.get(chunk);
        appended.writeBytes(chunk);
      }

      @Override
      public SegmentPublication publish() {
        var data = appended.toByteArray();
        if (SegmentNames.isInitSegment(segmentName)) {
          return publishInitialization(sessionId, segmentName, data);
        }

        addSegment(sessionId, segmentName, data);
        return SegmentPublication.PUBLISHED;
      }

      @Override
      public void close() {
        // The appended bytes stay on the heap until publish(), so there is nothing to release.
      }
    };
  }

  private SegmentPublication publishInitialization(
      UUID sessionId, String segmentName, byte[] data) {
    var stored =
        sessions
            .computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
            .putIfAbsent(segmentName, data);
    if (stored == null || Arrays.equals(stored, data)) {
      return SegmentPublication.PUBLISHED;
    }

    return SegmentPublication.INITIALIZATION_SEGMENT_DIFFERS;
  }

  @Override
  public void deleteSession(UUID sessionId) {
    sessions.remove(sessionId);
  }

  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>()).put(segmentName, data);
  }
}
