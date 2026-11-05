package com.streamarr.server.services.language;

import com.streamarr.server.domain.language.Language;

public sealed interface SetLanguagePreferencesRejection {

  /** The list names more audio languages than a Profile keeps. */
  record TooManyAudioLanguages(int maximum) implements SetLanguagePreferencesRejection {}

  /** The list names a language a second time, at the given position. */
  record RepeatedAudioLanguage(Language language, int position)
      implements SetLanguagePreferencesRejection {}
}
