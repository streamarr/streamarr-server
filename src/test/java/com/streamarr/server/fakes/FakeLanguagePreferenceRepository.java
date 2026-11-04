package com.streamarr.server.fakes;

import com.streamarr.server.domain.language.LanguageOverride;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideScope;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.TitleKind;
import com.streamarr.server.domain.language.TitleLineage;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps preferences and overrides in memory. Every Profile id exists, with default preferences
 * until set, unless a test deletes it. Lineages come from the media fakes, as the database derives
 * them from its tables.
 */
public class FakeLanguagePreferenceRepository implements LanguagePreferenceRepository {

  private final Map<UUID, ProfileLanguagePreferences> profilePreferences =
      new ConcurrentHashMap<>();
  private final Map<OverrideKey, LanguageOverride> overrides = new ConcurrentHashMap<>();
  private final AtomicInteger overrideReads = new AtomicInteger();
  private final Set<UUID> deletedProfiles = ConcurrentHashMap.newKeySet();
  private final FakeMovieRepository movies;
  private final FakeSeriesRepository series;
  private final FakeSeasonRepository seasons;
  private final FakeEpisodeRepository episodes;

  public FakeLanguagePreferenceRepository(
      FakeMovieRepository movies,
      FakeSeriesRepository series,
      FakeSeasonRepository seasons,
      FakeEpisodeRepository episodes) {
    this.movies = movies;
    this.series = series;
    this.seasons = seasons;
    this.episodes = episodes;
  }

  private record OverrideKey(UUID profileId, UUID titleId) {}

  public void deleteProfile(UUID profileId) {
    deletedProfiles.add(profileId);
    profilePreferences.remove(profileId);
  }

  /** How many reads of overrides the repository has served, each for any number of titles. */
  public int overrideReads() {
    return overrideReads.get();
  }

  /** The number of stored overrides, including other Profiles'. */
  public int overrideCount() {
    return overrides.size();
  }

  @Override
  public Optional<ProfileLanguagePreferences> findProfilePreferences(UUID profileId) {
    if (deletedProfiles.contains(profileId)) {
      return Optional.empty();
    }

    return Optional.of(
        profilePreferences.getOrDefault(profileId, ProfileLanguagePreferences.defaults()));
  }

  @Override
  public boolean trySetProfilePreferences(UUID profileId, ProfileLanguagePreferences preferences) {
    if (deletedProfiles.contains(profileId)) {
      return false;
    }

    profilePreferences.put(profileId, preferences);
    return true;
  }

  @Override
  public Map<UUID, TitleLineage> findLineages(Collection<UUID> titleIds) {
    var result = new HashMap<UUID, TitleLineage>();
    for (var titleId : titleIds) {
      lineageOf(titleId).ifPresent(lineage -> result.put(titleId, lineage));
    }

    return result;
  }

  private Optional<TitleLineage> lineageOf(UUID titleId) {
    if (movies.findById(titleId).isPresent()) {
      return Optional.of(new TitleLineage(List.of(scope(TitleKind.MOVIE, titleId))));
    }

    if (series.findById(titleId).isPresent()) {
      return Optional.of(new TitleLineage(List.of(scope(TitleKind.SERIES, titleId))));
    }

    return seasons
        .findById(titleId)
        .map(season -> new TitleLineage(seasonScopes(season)))
        .or(() -> episodes.findById(titleId).map(FakeLanguagePreferenceRepository::episodeLineage));
  }

  private static TitleLineage episodeLineage(Episode episode) {
    var scopes = new ArrayList<OverrideScope>();
    scopes.add(scope(TitleKind.EPISODE, episode.getId()));
    scopes.addAll(seasonScopes(episode.getSeason()));
    return new TitleLineage(scopes);
  }

  private static List<OverrideScope> seasonScopes(Season season) {
    return List.of(
        scope(TitleKind.SEASON, season.getId()),
        scope(TitleKind.SERIES, season.getSeries().getId()));
  }

  private static OverrideScope scope(TitleKind kind, UUID titleId) {
    return new OverrideScope(kind, titleId);
  }

  @Override
  public Map<UUID, LanguageOverride> findOverrides(UUID profileId, Collection<UUID> titleIds) {
    overrideReads.incrementAndGet();
    var result = new HashMap<UUID, LanguageOverride>();
    for (var titleId : titleIds) {
      var stored = overrides.get(new OverrideKey(profileId, titleId));
      if (stored != null) {
        result.put(titleId, stored);
      }
    }

    return result;
  }

  @Override
  public void changeOverride(UUID profileId, OverrideScope scope, LanguageOverrideChange change) {
    overrides.compute(
        new OverrideKey(profileId, scope.titleId()),
        (_, stored) -> {
          var changed =
              change.applyTo(stored == null ? LanguageOverride.inheritingEverything() : stored);
          return changed.inheritsEverything() ? null : changed;
        });
  }

  @Override
  public void deleteOverride(UUID profileId, OverrideScope scope) {
    overrides.remove(new OverrideKey(profileId, scope.titleId()));
  }
}
