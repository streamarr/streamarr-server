package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code SetProfileLanguagePreferencesError} union: a Profile the caller may not change, or a
 * refusal of the preferred audio languages.
 */
public sealed interface SetProfileLanguagePreferencesError extends MutationError
    permits ProfileNotFoundError, PreferredAudioLanguagesError {}
