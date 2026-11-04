package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import java.util.List;
import java.util.Optional;

public record SetLanguagePreferencesPayload(
    Optional<ProfileLanguagePreferences> languagePreferences,
    List<SetLanguagePreferencesError> userErrors) {}
