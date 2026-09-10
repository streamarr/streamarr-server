package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface CreateStreamSessionV2Error extends MutationError {

  record TranscodeCapacityUnavailableError(String message) implements CreateStreamSessionV2Error {}

  record InvalidIdError(String message, List<String> inputPath)
      implements CreateStreamSessionV2Error, InputMutationError {}

  record MediaFileNotFoundError(String message, List<String> inputPath)
      implements CreateStreamSessionV2Error, InputMutationError {}

  record MediaFileProbeNotReadyError(String message) implements CreateStreamSessionV2Error {}

  record InvalidMediaFileError(String message) implements CreateStreamSessionV2Error {}

  record MediaFileHasNoVideoError(String message) implements CreateStreamSessionV2Error {}
}
