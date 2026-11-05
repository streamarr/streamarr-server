package com.streamarr.server.graphql.dto;

import lombok.Builder;

/** A stream session as the schema's {@code StreamSession} interface spells it. */
public sealed interface StreamSessionDto {

  /**
   * {@code mediaEndSeconds} is null while nothing shows that the media ends before its playlist.
   * {@code startPositionSeconds} is null when playback starts at the beginning of the media.
   */
  @Builder
  record HlsStreamSession(
      String id,
      String streamUrl,
      String transcodeMode,
      String videoRange,
      Double mediaEndSeconds,
      Double startPositionSeconds)
      implements StreamSessionDto {}

  @Builder
  record DirectPlayStreamSession(
      String id,
      String streamUrl,
      String sourceContainer,
      String videoRange,
      Double startPositionSeconds)
      implements StreamSessionDto {}
}
