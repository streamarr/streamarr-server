package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.SegmentRequest;
import java.util.UUID;

public final class SegmentRequestFixture {

  private static final long BEFORE_EVERY_PREPARED_REQUEST = 0;

  private SegmentRequestFixture() {}

  /** A request that reached the server before every request that the lifecycle prepares. */
  public static SegmentRequest earliestArrivingSegmentRequest(
      UUID sessionId, String variantLabel, String segmentName) {
    return segmentRequestBuilder(sessionId, variantLabel, segmentName)
        .arrival(BEFORE_EVERY_PREPARED_REQUEST)
        .build();
  }

  public static SegmentRequest.SegmentRequestBuilder segmentRequestBuilder(
      UUID sessionId, String variantLabel, String segmentName) {
    return SegmentRequest.builder()
        .sessionId(sessionId)
        .variantLabel(variantLabel)
        .segmentName(segmentName);
  }
}
