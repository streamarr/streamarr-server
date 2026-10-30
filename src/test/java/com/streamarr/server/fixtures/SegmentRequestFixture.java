package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.SegmentRequest;
import java.util.UUID;

public final class SegmentRequestFixture {

  private SegmentRequestFixture() {}

  public static SegmentRequest segmentRequest(
      UUID sessionId, String variantLabel, String segmentName) {
    return SegmentRequest.builder()
        .sessionId(sessionId)
        .variantLabel(variantLabel)
        .segmentName(segmentName)
        .build();
  }
}
