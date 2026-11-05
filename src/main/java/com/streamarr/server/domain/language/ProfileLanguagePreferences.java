package com.streamarr.server.domain.language;

import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A Profile's own language preferences (ADR 0043): its audio languages in order of preference, at
 * most {@link #MAXIMUM_AUDIO_LANGUAGES} and none twice, its default subtitle language and its
 * subtitle mode.
 */
@Builder(toBuilder = true)
public record ProfileLanguagePreferences(
    @NonNull List<Language> audioLanguages,
    @NonNull Optional<Language> subtitleLanguage,
    @NonNull SubtitlePreferenceMode subtitleMode) {

  public static final int MAXIMUM_AUDIO_LANGUAGES = 5;

  public ProfileLanguagePreferences {
    audioLanguages = List.copyOf(audioLanguages);
  }

  /** The preferences of a Profile that has set none. */
  public static ProfileLanguagePreferences defaults() {
    return builder().build();
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class ProfileLanguagePreferencesBuilder {
    private List<Language> audioLanguages = List.of();
    private Optional<Language> subtitleLanguage = Optional.empty();
    private SubtitlePreferenceMode subtitleMode = SubtitlePreferenceMode.FORCED;
  }
}
