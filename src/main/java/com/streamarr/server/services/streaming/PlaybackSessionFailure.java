package com.streamarr.server.services.streaming;

import lombok.NonNull;

public sealed interface PlaybackSessionFailure {
  record OutputMismatch() implements PlaybackSessionFailure {}

  /** The worker rejected the job's specification, so every replacement attempt would fail too. */
  record InvalidSpecification() implements PlaybackSessionFailure {}

  /**
   * The worker could not convert a Dolby Vision profile 7 picture's RPU to profile 8.1, which every
   * replacement attempt of the same copy would repeat (streamarr/streamarr-server#651).
   */
  record DolbyVisionConversionFailed() implements PlaybackSessionFailure {}

  /**
   * An encoded attempt that read the source from a seek opened its first segment late, so pictures
   * are missing. Every attempt that reads the source the same way misses them too (ADR 0037, seek).
   */
  record FirstSegmentOpenedLate() implements PlaybackSessionFailure {}

  record FixedFormatFailed(@NonNull PlaybackFormatFailure reason)
      implements PlaybackSessionFailure {}

  record AttemptsExhausted() implements PlaybackSessionFailure {}

  record CreationRejected(@NonNull CreateStreamSessionRejection reason)
      implements PlaybackSessionFailure {}

  record StartupFailed() implements PlaybackSessionFailure {}
}
