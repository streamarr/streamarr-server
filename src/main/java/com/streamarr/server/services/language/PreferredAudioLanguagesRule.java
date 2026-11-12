package com.streamarr.server.services.language;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * The preferred audio languages a Profile may keep (ADR 0043): at most {@link
 * ProfileLanguagePreferences#MAXIMUM_AUDIO_LANGUAGES}, none twice. Whoever changes a Profile's
 * preferences checks them here.
 */
public final class PreferredAudioLanguagesRule {

  private PreferredAudioLanguagesRule() {}

  /** Why the Profile may not keep the audio languages; empty when it may. */
  public static Optional<SetLanguagePreferencesRejection> rejectionOf(
      @NonNull List<Language> audioLanguages) {
    if (audioLanguages.size() > ProfileLanguagePreferences.MAXIMUM_AUDIO_LANGUAGES) {
      return Optional.of(
          new SetLanguagePreferencesRejection.TooManyAudioLanguages(
              ProfileLanguagePreferences.MAXIMUM_AUDIO_LANGUAGES));
    }

    var seen = new HashSet<Language>();
    for (var position = 0; position < audioLanguages.size(); position++) {
      if (!seen.add(audioLanguages.get(position))) {
        return Optional.of(
            new SetLanguagePreferencesRejection.RepeatedAudioLanguage(
                audioLanguages.get(position), position));
      }
    }

    return Optional.empty();
  }
}
