package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface CreateStreamSessionError extends MutationError {

  record TranscodeCapacityUnavailableError(String message) implements CreateStreamSessionError {}

  record NoEligibleVideoFormatError(String message) implements CreateStreamSessionError {}

  record InvalidIdError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record MediaFileNotFoundError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record MediaFileProbeNotReadyError(String message) implements CreateStreamSessionError {}

  record InvalidMediaFileError(String message) implements CreateStreamSessionError {}

  record MediaFileHasNoVideoError(String message) implements CreateStreamSessionError {}

  record MediaFileFrameRateUnknownError(String message) implements CreateStreamSessionError {}

  record CapabilityValuesRequiredError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record HlsAacCapabilityRequiredError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record CapabilityListTooLongError(String message, List<String> inputPath, int maximum)
      implements CreateStreamSessionError, InputMutationError {}

  record CapabilityMaximumInvalidError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record CapabilityProfileMismatchError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}

  record VideoCodecTierInvalidError(String message, List<String> inputPath)
      implements CreateStreamSessionError, InputMutationError {}
}
