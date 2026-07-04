package com.streamarr.server.services.watchprogress;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.time.Instant;
import lombok.Builder;

@Builder
public record WatchProgressDto(
    int positionSeconds, double percentComplete, int durationSeconds, Instant lastModifiedOn) {

  public static WatchProgressDto from(SessionProgress sessionProgress) {
    return WatchProgressDto.builder()
        .positionSeconds(sessionProgress.getPositionSeconds())
        .percentComplete(sessionProgress.getPercentComplete())
        .durationSeconds(sessionProgress.getDurationSeconds())
        .lastModifiedOn(sessionProgress.getLastModifiedOn())
        .build();
  }
}
