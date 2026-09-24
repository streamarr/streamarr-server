package com.streamarr.server.fixtures.mesh;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.SegmentNames;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class MeshSegmentStore extends FakeSegmentStore {

  private final Map<UUID, CompletableFuture<byte[]>> firstSegments = new ConcurrentHashMap<>();

  /** The initialization segment followed by the first media segment, which decode together. */
  byte[] awaitFirstSegment(UUID sessionId) throws Exception {
    return firstSegments
        .computeIfAbsent(sessionId, _ -> new CompletableFuture<>())
        .get(30, TimeUnit.SECONDS);
  }

  @Override
  public void addSegment(UUID sessionId, String segmentName, byte[] data) {
    super.addSegment(sessionId, segmentName, data);
    if (segmentName.equals(SegmentNames.mediaSegment(0))) {
      firstSegments
          .computeIfAbsent(sessionId, _ -> new CompletableFuture<>())
          .complete(afterInitialization(sessionId, data));
    }
  }

  // The worker uploads a variant's initialization segment before its first media segment.
  private byte[] afterInitialization(UUID sessionId, byte[] mediaSegment) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT));
    media.writeBytes(mediaSegment);
    return media.toByteArray();
  }

  @Override
  public void deleteSession(UUID sessionId) {
    firstSegments.remove(sessionId);
    super.deleteSession(sessionId);
  }
}
