package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.util.UUID;
import lombok.NonNull;

public sealed interface PlaybackFormatAttempt {
  UUID streamSessionId();

  record Pending(@NonNull UUID streamSessionId) implements PlaybackFormatAttempt {}

  record Ready(@NonNull StreamSession session) implements PlaybackFormatAttempt {
    @Override
    public UUID streamSessionId() {
      return session.getSessionId();
    }
  }

  record Failed(@NonNull UUID streamSessionId, @NonNull PlaybackSessionFailure reason)
      implements PlaybackFormatAttempt {}
}
