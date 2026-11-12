package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import java.util.List;

public record SetProfileLanguagePreferencesInput(
    String profileId,
    List<Language> audioLanguages,
    Language subtitleLanguage,
    SubtitlePreferenceMode subtitleMode) {}
