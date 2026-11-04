package com.streamarr.server.graphql.dto;

import lombok.Builder;

/** A stream session as the schema's {@code StreamSession} interface spells it. */
public sealed interface StreamSessionDto {

  /**
   * {@code mediaEndSeconds} is null while nothing shows that the media ends before its playlist.
   */
  @Builder
  record HlsStreamSession(
      String id, String streamUrl, String transcodeMode, String videoRange, Double mediaEndSeconds)
      implements StreamSessionDto {}

  /** {@code audioTrackPosition} is null for a file without audio. */
  @Builder
  record DirectPlayStreamSession(
      String id,
      String streamUrl,
      String sourceContainer,
      String videoRange,
      Integer audioTrackPosition)
      implements StreamSessionDto {}
}
