package com.streamarr.server.fixtures.mesh;

import com.streamarr.server.fakes.FakeSegmentStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class MeshSegmentStore extends FakeSegmentStore {

  private final Map<UUID, CompletableFuture<byte[]>> firstSegments = new ConcurrentHashMap<>();

  byte[] awaitFirstSegment(UUID sessionId) throws Exception {
    return firstSegments
        .computeIfAbsent(sessionId, _ -> new CompletableFuture<>())
        .get(30, TimeUnit.SECONDS);
  }

  @Override
  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    super.addSegment(sessionId, segmentName, data);
    if (segmentName.equals("segment0.ts")) {
      firstSegments.computeIfAbsent(sessionId, _ -> new CompletableFuture<>()).complete(data);
    }
  }

  @Override
  public void deleteSession(UUID sessionId) {
    firstSegments.remove(sessionId);
    super.deleteSession(sessionId);
  }
}
