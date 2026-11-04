package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

/** The members of the setLanguageOverride and clearLanguageOverride error unions. */
public sealed interface LanguageOverrideError extends MutationError {

  record InvalidIdError(String message, List<String> inputPath)
      implements LanguageOverrideError, InputMutationError {}

  record ItemNotFoundError(String message, List<String> inputPath)
      implements LanguageOverrideError, InputMutationError {}
}
