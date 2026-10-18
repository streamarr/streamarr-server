package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.services.streaming.PlaybackFormatFailure;

public sealed interface PlaybackSessionError extends MutationError {

  record PlaybackSessionClosedError(String message) implements PlaybackSessionError {}

  record PlaybackOutputMismatchError(String message) implements PlaybackSessionError {}

  record PlaybackFormatRejectedError(String message, PlaybackFormatFailure reason)
      implements PlaybackSessionError {}

  record PlaybackFormatAttemptsExhaustedError(String message) implements PlaybackSessionError {}

  record PlaybackStartupFailedError(String message) implements PlaybackSessionError {}
}
