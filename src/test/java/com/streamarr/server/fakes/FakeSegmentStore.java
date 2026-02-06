package com.streamarr.server.fakes;

import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.services.streaming.SegmentStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FakeSegmentStore implements SegmentStore {

  private final Map<UUID, Map<String, byte[]>> sessions = new HashMap<>();

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
  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    var segments = sessions.get(sessionId);
    return segments != null && segments.containsKey(segmentName);
  }

  @Override
  public void deleteSession(UUID sessionId) {
    sessions.remove(sessionId);
  }

  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    sessions.computeIfAbsent(sessionId, id -> new HashMap<>()).put(segmentName, data);
  }
}
