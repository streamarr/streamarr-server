package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/**
 * Why a Profile may not keep the audio languages a mutation names, the same in every mutation that
 * sets them. Record names are the schema type names DGS resolves by.
 */
public sealed interface PreferredAudioLanguagesError
    extends SetLanguagePreferencesError, SetProfileLanguagePreferencesError, InputMutationError {

  record TooManyAudioLanguagesError(String message, List<String> inputPath, int maximum)
      implements PreferredAudioLanguagesError {}

  record RepeatedAudioLanguageError(String message, List<String> inputPath, Language language)
      implements PreferredAudioLanguagesError {}
}
