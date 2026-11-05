package com.streamarr.server.domain.language;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.Builder;
import lombok.NonNull;

/**
 * The language preferences that apply to one title for one Profile (ADR 0043). {@code
 * audioLanguages} lists the languages in the order the stream decision tries them: the episode's or
 * movie's override, the season's, the series', then the Profile's list, each language once. {@code
 * audioSource} is the level of the first of them. {@code subtitleSource} is the most specific level
 * that sets the subtitle language or the subtitle mode.
 */
@Builder
public record LanguagePreference(
    @NonNull List<Language> audioLanguages,
    @NonNull Optional<Language> subtitleLanguage,
    @NonNull SubtitlePreferenceMode subtitleMode,
    @NonNull PreferenceSource audioSource,
    @NonNull PreferenceSource subtitleSource) {

  public LanguagePreference {
    audioLanguages = List.copyOf(audioLanguages);
  }

  /**
   * The preference that applies to a title of the lineage, from the Profile's overrides keyed by
   * title id and the Profile's own preferences.
   */
  public static LanguagePreference of(
      @NonNull TitleLineage lineage,
      @NonNull Map<UUID, LanguageOverride> overrides,
      @NonNull ProfileLanguagePreferences profile) {
    var scoped =
        lineage.scopes().stream()
            .filter(scope -> overrides.containsKey(scope.titleId()))
            .map(scope -> new ScopedOverride(scope.kind().source(), overrides.get(scope.titleId())))
            .toList();

    var audioLanguages = new LinkedHashSet<Language>();
    scoped.forEach(entry -> entry.override().audioLanguage().ifPresent(audioLanguages::add));
    audioLanguages.addAll(profile.audioLanguages());

    return LanguagePreference.builder()
        .audioLanguages(List.copyOf(audioLanguages))
        .audioSource(firstSourceWhere(scoped, override -> override.audioLanguage().isPresent()))
        .subtitleLanguage(
            firstSet(scoped, LanguageOverride::subtitleLanguage).or(profile::subtitleLanguage))
        .subtitleMode(
            firstSet(scoped, LanguageOverride::subtitleMode).orElse(profile.subtitleMode()))
        .subtitleSource(firstSourceWhere(scoped, LanguageOverride::setsSubtitles))
        .build();
  }

  private record ScopedOverride(PreferenceSource source, LanguageOverride override) {}

  private static <T> Optional<T> firstSet(
      List<ScopedOverride> scoped, Function<LanguageOverride, Optional<T>> field) {
    return scoped.stream()
        .map(entry -> field.apply(entry.override()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private static PreferenceSource firstSourceWhere(
      List<ScopedOverride> scoped, Predicate<LanguageOverride> sets) {
    return scoped.stream()
        .filter(entry -> sets.test(entry.override()))
        .map(ScopedOverride::source)
        .findFirst()
        .orElse(PreferenceSource.PROFILE);
  }
}
