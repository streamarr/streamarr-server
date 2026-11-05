package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code ChangeIdleSegmentRetentionError} union; record names are the schema type names. */
public sealed interface ChangeIdleSegmentRetentionError extends InputMutationError {

  record IdleSegmentRetentionOutOfRangeError(String message, List<String> inputPath)
      implements ChangeIdleSegmentRetentionError {}
}
