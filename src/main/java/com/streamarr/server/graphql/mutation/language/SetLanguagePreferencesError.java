package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code SetLanguagePreferencesError} union: a refusal of the preferred audio languages. */
public sealed interface SetLanguagePreferencesError extends MutationError
    permits PreferredAudioLanguagesError {}
