package com.streamarr.server.fixtures.mesh;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.services.streaming.SegmentNames;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class MeshSegmentStore extends FakeSegmentStore {

  private final Map<UUID, CompletableFuture<byte[]>> firstSegments = new ConcurrentHashMap<>();

  /** The initialization segment followed by the first media segment, which decode together. */
  byte[] awaitFirstDecodableMedia(UUID sessionId) throws Exception {
    return firstSegments
        .computeIfAbsent(sessionId, _ -> new CompletableFuture<>())
        .get(30, TimeUnit.SECONDS);
  }

  @Override
  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    super.addSegment(sessionId, segmentName, data);
    if (segmentName.equals(SegmentNames.mediaSegmentName(0))) {
      firstSegments
          .computeIfAbsent(sessionId, _ -> new CompletableFuture<>())
          .complete(Fmp4Fixture.withInitializationSegment(this, sessionId, data));
    }
  }

  @Override
  public void deleteSession(UUID sessionId) {
    firstSegments.remove(sessionId);
    super.deleteSession(sessionId);
  }
}
