package com.streamarr.server.graphql.mutation.playbackreport;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

/** The {@code MarkPlaybackReportReadError} union; record names are the schema type names. */
public sealed interface MarkPlaybackReportReadError extends MutationError {

  record InvalidIdError(String message, List<String> inputPath)
      implements MarkPlaybackReportReadError, InputMutationError {}

  record PlaybackReportNotFoundError(String message, List<String> inputPath)
      implements MarkPlaybackReportReadError, InputMutationError {}
}
