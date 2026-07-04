package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.SessionProgress;
import java.util.UUID;

public final class SessionProgressFixture {

  private SessionProgressFixture() {}

  public static SessionProgress.SessionProgressBuilder progressBuilder(
      UUID userId, UUID mediaFileId) {
    return SessionProgress.builder()
        .sessionId(UUID.randomUUID())
        .userId(userId)
        .mediaFileId(mediaFileId)
        .percentComplete(50.0)
        .durationSeconds(7200);
  }
}
