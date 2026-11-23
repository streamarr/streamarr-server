package com.streamarr.server.graphql.mutation.playbackreport;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

/** The {@code SharePlaybackReportError} union; record names are the schema type names. */
public sealed interface SharePlaybackReportError extends MutationError {

  record InvalidIdError(String message, List<String> inputPath)
      implements SharePlaybackReportError, InputMutationError {}

  record PlaybackSessionClosedError(String message) implements SharePlaybackReportError {}

  record PlaybackReportNoteTooLongError(String message, List<String> inputPath, int maximumLength)
      implements SharePlaybackReportError, InputMutationError {}

  record PlayerFigureInvalidError(String message, List<String> inputPath)
      implements SharePlaybackReportError, InputMutationError {}
}
