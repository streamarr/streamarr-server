package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileActivityDetails(
    UUID id,
    UUID mediaFileId,
    int positionSeconds,
    double percentComplete,
    int durationSeconds,
    String watchedAt) {

  public static ProfileActivityDetails from(SessionProgress progress) {
    return ProfileActivityDetails.builder()
        .id(progress.getId())
        .mediaFileId(progress.getMediaFileId())
        .positionSeconds(progress.getPositionSeconds())
        .percentComplete(progress.getPercentComplete())
        .durationSeconds(progress.getDurationSeconds())
        .watchedAt(progress.getLastModifiedOn().toString())
        .build();
  }
}
