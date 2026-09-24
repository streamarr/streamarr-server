package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Fmp4Fixture {

  private Fmp4Fixture() {}

  /** The type of the first ISOBMFF box: {@code ftyp} opens an initialization segment. */
  public static String firstBoxType(byte[] media) {
    assertThat(media).hasSizeGreaterThanOrEqualTo(8);
    return new String(media, 4, 4, StandardCharsets.US_ASCII);
  }

  /**
   * The stream session's stored initialization segment followed by the media segment, which a
   * player decodes together.
   */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, byte[] mediaSegment) {
    var media = new ByteArrayOutputStream();
    media.writeBytes(segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    media.writeBytes(mediaSegment);
    return media.toByteArray();
  }

  /** The stream session's stored initialization segment followed by its stored media segment. */
  public static byte[] withInitializationSegment(
      SegmentStore segmentStore, UUID sessionId, String mediaSegmentName) {
    return withInitializationSegment(
        segmentStore, sessionId, segmentStore.readSegment(sessionId, mediaSegmentName));
  }
}
