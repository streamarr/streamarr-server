package com.streamarr.server.domain.language;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * A Profile's override of its language preferences for one series, season, episode or movie. An
 * empty field inherits from the next wider level. An override names a language, never a track.
 */
@Builder(toBuilder = true)
public record LanguageOverride(
    @NonNull Optional<Language> audioLanguage,
    @NonNull Optional<Language> subtitleLanguage,
    @NonNull Optional<SubtitlePreferenceMode> subtitleMode) {

  public static LanguageOverride inheritingEverything() {
    return builder().build();
  }

  /** Whether every field inherits from the next wider level. */
  public boolean inheritsEverything() {
    return audioLanguage.isEmpty() && !setsSubtitles();
  }

  /** Whether the override sets the subtitle language or the subtitle mode. */
  public boolean setsSubtitles() {
    return subtitleLanguage.isPresent() || subtitleMode.isPresent();
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class LanguageOverrideBuilder {
    private Optional<Language> audioLanguage = Optional.empty();
    private Optional<Language> subtitleLanguage = Optional.empty();
    private Optional<SubtitlePreferenceMode> subtitleMode = Optional.empty();
  }
}
