package com.streamarr.server.services.language;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguagePreference;
import com.streamarr.server.domain.language.OverrideScope;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.TitleKind;
import com.streamarr.server.domain.language.TitleLineage;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.PlaybackLanguagePreferences;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Profile's language preferences and its overrides of them for one series, season, episode or
 * movie (ADR 0043). The selected Profile changes its own preferences only.
 */
@Service
@RequiredArgsConstructor
public class LanguagePreferenceService implements PlaybackLanguagePreferences {

  private static final TitleLineage NO_TITLE = new TitleLineage(List.of());

  private final AuthorizationService authorizationService;
  private final LanguagePreferenceRepository languagePreferenceRepository;
  private final MovieRepository movieRepository;
  private final SeriesRepository seriesRepository;
  private final SeasonRepository seasonRepository;
  private final EpisodeRepository episodeRepository;

  /** The selected Profile's own preferences; empty when the session has no selected Profile. */
  @Transactional(readOnly = true)
  public Optional<ProfileLanguagePreferences> profilePreferences(
      @NonNull AuthenticatedIdentity identity) {
    if (identity.profileId() == null) {
      return Optional.empty();
    }

    return Optional.of(
        storedPreferencesOf(authorizationService.requireViewProfileActivity(identity)));
  }

  /** Replaces the selected Profile's own preferences. */
  @Transactional
  public Outcome<ProfileLanguagePreferences, SetLanguagePreferencesRejection> setProfilePreferences(
      @NonNull AuthenticatedIdentity identity, @NonNull ProfileLanguagePreferences preferences) {
    var profileId = requireMayChangeLanguages(identity);
    var rejection = invalidAudioLanguages(preferences.audioLanguages());
    if (rejection.isPresent()) {
      return Outcome.rejected(rejection.get());
    }

    if (!languagePreferenceRepository.trySetProfilePreferences(profileId, preferences)) {
      throw new ProfileRequiredException();
    }

    return Outcome.accepted(preferences);
  }

  private static Optional<SetLanguagePreferencesRejection> invalidAudioLanguages(
      List<Language> audioLanguages) {
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

  /** Changes the selected Profile's override of one series, season, episode or movie. */
  @Transactional
  public Outcome<BaseCollectable<?>, LanguageOverrideRejection> setLanguageOverride(
      @NonNull AuthenticatedIdentity identity, @NonNull SetLanguageOverrideCommand command) {
    return withOverriddenTitle(
        identity,
        command.itemId(),
        (profileId, scope) ->
            languagePreferenceRepository.changeOverride(profileId, scope, command.change()));
  }

  /** Removes the selected Profile's override of the title, so the wider level's choice applies. */
  @Transactional
  public Outcome<BaseCollectable<?>, LanguageOverrideRejection> clearLanguageOverride(
      @NonNull AuthenticatedIdentity identity, @NonNull UUID itemId) {
    return withOverriddenTitle(identity, itemId, languagePreferenceRepository::deleteOverride);
  }

  private Outcome<BaseCollectable<?>, LanguageOverrideRejection> withOverriddenTitle(
      AuthenticatedIdentity identity, UUID itemId, BiConsumer<UUID, OverrideScope> write) {
    var profileId = requireMayChangeLanguages(identity);
    var title = findTitle(itemId);
    if (title.isEmpty()) {
      return Outcome.rejected(new LanguageOverrideRejection.ItemNotFound(itemId));
    }

    write.accept(profileId, new OverrideScope(title.get().kind(), itemId));
    return Outcome.accepted(title.get().title());
  }

  /**
   * The preference that applies to each title for the Profile, read in one batch. An id that names
   * no title gets the Profile's own preferences.
   */
  @Transactional(readOnly = true)
  public Map<UUID, LanguagePreference> languagePreferences(
      @NonNull UUID profileId, @NonNull Collection<UUID> titleIds) {
    return preferencesOf(profileId, titleIds);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Language> audioLanguagesFor(@NonNull UUID profileId, @NonNull MediaFile file) {
    var titleId = file.getMediaId();
    if (titleId == null) {
      return storedPreferencesOf(profileId).audioLanguages();
    }

    return preferencesOf(profileId, List.of(titleId)).get(titleId).audioLanguages();
  }

  private Map<UUID, LanguagePreference> preferencesOf(UUID profileId, Collection<UUID> titleIds) {
    var lineages = languagePreferenceRepository.findLineages(titleIds);
    var scopedTitleIds =
        lineages.values().stream()
            .flatMap(lineage -> lineage.scopes().stream())
            .map(OverrideScope::titleId)
            .distinct()
            .toList();
    var overrides = languagePreferenceRepository.findOverrides(profileId, scopedTitleIds);
    var profile = storedPreferencesOf(profileId);

    var result = new HashMap<UUID, LanguagePreference>();
    for (var titleId : titleIds) {
      var lineage = lineages.getOrDefault(titleId, NO_TITLE);
      result.put(titleId, LanguagePreference.of(lineage, overrides, profile));
    }

    return result;
  }

  private UUID requireMayChangeLanguages(AuthenticatedIdentity identity) {
    var profileId = authorizationService.requireProfile(identity);
    authorizationService.requireAllowed(identity, new Intent.SetLanguagePreferences(profileId));
    return profileId;
  }

  private ProfileLanguagePreferences storedPreferencesOf(UUID profileId) {
    return languagePreferenceRepository
        .findProfilePreferences(profileId)
        .orElseGet(ProfileLanguagePreferences::defaults);
  }

  private record FoundTitle(BaseCollectable<?> title, TitleKind kind) {}

  private Optional<FoundTitle> findTitle(UUID itemId) {
    return movieRepository
        .findById(itemId)
        .map(movie -> new FoundTitle(movie, TitleKind.MOVIE))
        .or(() -> seriesRepository.findById(itemId).map(s -> new FoundTitle(s, TitleKind.SERIES)))
        .or(() -> seasonRepository.findById(itemId).map(s -> new FoundTitle(s, TitleKind.SEASON)))
        .or(
            () ->
                episodeRepository
                    .findById(itemId)
                    .map(episode -> new FoundTitle(episode, TitleKind.EPISODE)));
  }
}
