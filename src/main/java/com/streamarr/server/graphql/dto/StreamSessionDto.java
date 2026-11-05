package com.streamarr.server.graphql.dto;

import java.util.List;
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
      Double startPositionSeconds,
      String uploadBudgetUse)
      implements StreamSessionDto {}

  /** {@code audioTrackPosition} is null for a file without audio. */
  @Builder
  record DirectPlayStreamSession(
      String id,
      String streamUrl,
      String sourceContainer,
      String videoRange,
      Double startPositionSeconds,
      Integer audioTrackPosition,
      List<AudioTrack> audioTracks,
      String uploadBudgetUse)
      implements StreamSessionDto {}

  /** {@code language} and {@code title} are null when the file gives none. */
  @Builder
  record AudioTrack(int position, String language, String title) {}
}
