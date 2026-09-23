package com.streamarr.server.fakes;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeSegmentStore implements SegmentStore {

  private final Map<UUID, Map<String, byte[]>> sessions = new ConcurrentHashMap<>();

  @Override
  public byte[] readSegment(UUID sessionId, String segmentName) {
    var segments = sessions.get(sessionId);
    if (segments == null) {
      throw new TranscodeException("No output directory for session: " + sessionId);
    }
    var data = segments.get(segmentName);
    if (data == null) {
      throw new TranscodeException("Segment not found: " + segmentName);
    }
    return data;
  }

  @Override
  public boolean segmentExists(UUID sessionId, String segmentName) {
    return sessions.getOrDefault(sessionId, Map.of()).containsKey(segmentName);
  }

  @Override
  public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
    return new PreparedSegment() {
      @Override
      public SegmentPublication publish() {
        if (SegmentNames.isInitSegment(segmentName)) {
          return publishInitialization(sessionId, segmentName, data);
        }

        addSegment(sessionId, segmentName, data);
        return SegmentPublication.PUBLISHED;
      }

      @Override
      public void close() {
        // Data remains owned by this object until publish(), so there is no staged resource to
        // release.
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
