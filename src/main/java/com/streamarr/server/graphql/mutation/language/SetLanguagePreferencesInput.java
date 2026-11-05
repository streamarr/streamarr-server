package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import java.util.List;

public record SetLanguagePreferencesInput(
    List<Language> audioLanguages,
    Language subtitleLanguage,
    SubtitlePreferenceMode subtitleMode) {}
