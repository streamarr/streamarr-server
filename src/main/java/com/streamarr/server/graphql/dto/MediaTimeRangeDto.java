package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.DecimalSeconds;
import com.streamarr.server.domain.streaming.MediaTimeRange;

public record MediaTimeRangeDto(double startSeconds, double endSeconds) {

  public static MediaTimeRangeDto from(MediaTimeRange range) {
    return new MediaTimeRangeDto(
        DecimalSeconds.of(range.start()).doubleValue(),
        DecimalSeconds.of(range.end()).doubleValue());
  }
}
