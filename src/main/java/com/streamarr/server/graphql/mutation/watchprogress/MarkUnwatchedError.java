package com.streamarr.server.graphql.mutation.watchprogress;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface MarkUnwatchedError extends MutationError {

  record InvalidIdError(String message, List<String> inputPath)
      implements MarkUnwatchedError, InputMutationError {}

  record ItemNotFoundError(String message, List<String> inputPath)
      implements MarkUnwatchedError, InputMutationError {}
}
