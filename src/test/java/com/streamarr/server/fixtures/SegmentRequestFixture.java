package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.SegmentRequest;
import java.util.UUID;

public final class SegmentRequestFixture {

  private SegmentRequestFixture() {}

  /** A request older than every request that the lifecycle prepares. */
  public static SegmentRequest segmentRequest(
      UUID sessionId, String variantLabel, String segmentName) {
    return segmentRequestBuilder(sessionId, variantLabel, segmentName).build();
  }

  public static SegmentRequest.SegmentRequestBuilder segmentRequestBuilder(
      UUID sessionId, String variantLabel, String segmentName) {
    return SegmentRequest.builder()
        .sessionId(sessionId)
        .variantLabel(variantLabel)
        .segmentName(segmentName);
  }
}
