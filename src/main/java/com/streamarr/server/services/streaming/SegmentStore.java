package com.streamarr.server.services.streaming;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

public interface SegmentStore {

  byte[] readSegment(UUID sessionId, String segmentName);

  boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout);

  Path getOutputDirectory(UUID sessionId);

  Path getOutputDirectory(UUID sessionId, String variantLabel);

  void deleteSession(UUID sessionId);
}
