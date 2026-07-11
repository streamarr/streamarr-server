package com.streamarr.server.services.streaming;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public interface SegmentStore {

  byte[] readSegment(UUID sessionId, String segmentName);

  boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout);

  boolean segmentExists(UUID sessionId, String segmentName);

  Set<UUID> snapshotStoredSessionIds();

  void deleteSession(UUID sessionId);
}
