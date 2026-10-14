package com.streamarr.server.graphql.mutation.encoding;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code ChangeEncoderPresetError} union; record names are the schema type names. */
public sealed interface ChangeEncoderPresetError extends InputMutationError {

  record UnknownEncoderPresetError(String message, List<String> inputPath)
      implements ChangeEncoderPresetError {}
}
