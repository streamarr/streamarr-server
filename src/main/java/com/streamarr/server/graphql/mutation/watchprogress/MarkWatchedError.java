package com.streamarr.server.graphql.mutation.watchprogress;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface MarkWatchedError extends MutationError {

  record InvalidIdError(String message, List<String> inputPath)
      implements MarkWatchedError, InputMutationError {}

  record ItemNotFoundError(String message, List<String> inputPath)
      implements MarkWatchedError, InputMutationError {}
}
