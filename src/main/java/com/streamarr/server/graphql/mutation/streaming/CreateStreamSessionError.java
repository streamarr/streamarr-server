package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface CreateStreamSessionError extends MutationError {

  record TranscodeCapacityUnavailableError(String message) implements CreateStreamSessionError {}

  record InvalidIdError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record MediaFileNotFoundError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record MediaFileProbeNotReadyError(String message) implements CreateStreamSessionError {}

  record InvalidMediaFileError(String message) implements CreateStreamSessionError {}

  record MediaFileHasNoVideoError(String message) implements CreateStreamSessionError {}
}
