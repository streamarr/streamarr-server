package com.streamarr.server.services.streaming;

import lombok.NonNull;

public sealed interface PlaybackSessionFailure {
  record OutputMismatch() implements PlaybackSessionFailure {}

  record FixedFormatFailed(@NonNull PlaybackFormatFailure reason)
      implements PlaybackSessionFailure {}

  record AttemptsExhausted() implements PlaybackSessionFailure {}

  record CreationRejected(@NonNull CreateStreamSessionRejection reason)
      implements PlaybackSessionFailure {}

  record StartupFailed() implements PlaybackSessionFailure {}
}
