package com.streamarr.server.repositories.streaming;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

public sealed interface SaveProgressCommand {
  UUID userId();

  UUID mediaFileId();

  int positionSeconds();

  double percentComplete();

  int durationSeconds();

  @Builder
  record UpdateProgress(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds)
      implements SaveProgressCommand {}

  @Builder
  record MarkWatched(
      UUID userId,
      UUID mediaFileId,
      int positionSeconds,
      double percentComplete,
      int durationSeconds,
      Instant watchedAt)
      implements SaveProgressCommand {}
}
