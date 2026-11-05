package com.streamarr.server.domain.language;

import lombok.Builder;
import lombok.NonNull;

/** A change to a Profile's language override of one title, one change per field. */
@Builder
public record LanguageOverrideChange(
    @NonNull OverrideFieldChange<Language> audioLanguage,
    @NonNull OverrideFieldChange<Language> subtitleLanguage,
    @NonNull OverrideFieldChange<SubtitlePreferenceMode> subtitleMode) {

  /** The override after the change, from the override stored before it. */
  public LanguageOverride applyTo(@NonNull LanguageOverride stored) {
    return LanguageOverride.builder()
        .audioLanguage(audioLanguage.applyTo(stored.audioLanguage()))
        .subtitleLanguage(subtitleLanguage.applyTo(stored.subtitleLanguage()))
        .subtitleMode(subtitleMode.applyTo(stored.subtitleMode()))
        .build();
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class LanguageOverrideChangeBuilder {
    private OverrideFieldChange<Language> audioLanguage = OverrideFieldChange.keep();
    private OverrideFieldChange<Language> subtitleLanguage = OverrideFieldChange.keep();
    private OverrideFieldChange<SubtitlePreferenceMode> subtitleMode = OverrideFieldChange.keep();
  }
}
