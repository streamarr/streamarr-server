package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.SegmentRequest;

public final class SegmentRequestFixture {

  private static final long BEFORE_EVERY_PREPARED_REQUEST = 0;

  private SegmentRequestFixture() {}

  /**
   * A request for the default variant that reached the server before every request that the
   * lifecycle prepares. The caller names the session and the segment.
   */
  public static SegmentRequest.SegmentRequestBuilder earliestArrivingSegmentRequestBuilder() {
    return SegmentRequest.builder()
        .variantLabel(StreamSession.defaultVariant())
        .arrival(BEFORE_EVERY_PREPARED_REQUEST);
  }
}
