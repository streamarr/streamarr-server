package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.graphql.mutation.InputMutationError;
import com.streamarr.server.graphql.mutation.MutationError;
import java.util.List;

public sealed interface SetLanguagePreferencesError extends MutationError {

  record TooManyAudioLanguagesError(String message, List<String> inputPath, int maximum)
      implements SetLanguagePreferencesError, InputMutationError {}

  record RepeatedAudioLanguageError(String message, List<String> inputPath, Language language)
      implements SetLanguagePreferencesError, InputMutationError {}
}
