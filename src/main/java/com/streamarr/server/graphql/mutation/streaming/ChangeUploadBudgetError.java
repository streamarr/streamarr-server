package com.streamarr.server.graphql.mutation.streaming;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code ChangeUploadBudgetError} union; record names are the schema type names. */
public sealed interface ChangeUploadBudgetError extends InputMutationError {

  record UploadBudgetOutOfRangeError(String message, List<String> inputPath)
      implements ChangeUploadBudgetError {}
}
