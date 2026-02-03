package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.SegmentStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FakeSegmentStore implements SegmentStore {

  private final Map<UUID, Map<String, byte[]>> sessions = new HashMap<>();
  private final Map<UUID, Path> outputDirs = new HashMap<>();

  @Override
  public byte[] readSegment(UUID sessionId, String segmentName) {
    var segments = sessions.get(sessionId);
    if (segments == null) {
      return null;
    }
    return segments.get(segmentName);
  }

  @Override
  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    var segments = sessions.get(sessionId);
    return segments != null && segments.containsKey(segmentName);
  }

  @Override
  public Path getOutputDirectory(UUID sessionId) {
    return outputDirs.computeIfAbsent(sessionId, id -> Path.of("/tmp/fake-segments/" + id));
  }

  @Override
  public Path getOutputDirectory(UUID sessionId, String variantLabel) {
    return getOutputDirectory(sessionId).resolve(variantLabel);
  }

  @Override
  public void deleteSession(UUID sessionId) {
    sessions.remove(sessionId);
    outputDirs.remove(sessionId);
  }

  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    sessions.computeIfAbsent(sessionId, id -> new HashMap<>()).put(segmentName, data);
  }
}
