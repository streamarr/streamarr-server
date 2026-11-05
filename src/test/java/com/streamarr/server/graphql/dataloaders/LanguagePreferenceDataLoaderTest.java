package com.streamarr.server.graphql.dataloaders;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.LanguageOverrideChange;
import com.streamarr.server.domain.language.OverrideFieldChange;
import com.streamarr.server.domain.language.PreferenceSource;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeLanguagePreferenceRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.services.language.LanguagePreferenceService;
import com.streamarr.server.services.language.SetLanguageOverrideCommand;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Language Preference DataLoader Tests")
class LanguagePreferenceDataLoaderTest {

  private final FakeSeriesRepository seriesRepository = new FakeSeriesRepository();
  private final FakeSeasonRepository seasons = new FakeSeasonRepository();
  private final FakeEpisodeRepository episodes = new FakeEpisodeRepository();
  private final FakeMovieRepository movies = new FakeMovieRepository();
  private final FakeLanguagePreferenceRepository repository =
      new FakeLanguagePreferenceRepository(movies, seriesRepository, seasons, episodes);
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(profileScopedBuilder().build());
  private final LanguagePreferenceService service =
      new LanguagePreferenceService(
          authorization, repository, movies, seriesRepository, seasons, episodes);
  private final LanguagePreferenceDataLoader loader = new LanguagePreferenceDataLoader(service);

  @Test
  @DisplayName("Should read a page of episodes in one batch when each Profile's keys are loaded")
  void shouldReadAPageOfEpisodesInOneBatchWhenEachProfilesKeysAreLoaded() throws Exception {
    var page = seasonOfEpisodes(10);
    var viewer = authorization.currentIdentity();
    service.setLanguageOverride(
        viewer,
        SetLanguageOverrideCommand.builder()
            .itemId(page.getFirst().getSeason().getSeries().getId())
            .change(
                LanguageOverrideChange.builder()
                    .audioLanguage(OverrideFieldChange.replaceWith(Optional.of(Language.JA)))
                    .build())
            .build());
    var housemate = UUID.randomUUID();
    var keys = new HashSet<LanguagePreferenceLoaderKey>();
    page.forEach(
        episode -> {
          keys.add(new LanguagePreferenceLoaderKey(viewer.profileId(), episode.getId()));
          keys.add(new LanguagePreferenceLoaderKey(housemate, episode.getId()));
        });

    var preferences = loader.load(keys).toCompletableFuture().get();

    assertThat(repository.overrideReads()).isEqualTo(2);
    assertThat(preferences).hasSize(20);
    page.forEach(
        episode -> {
          var viewerPreference =
              preferences.get(new LanguagePreferenceLoaderKey(viewer.profileId(), episode.getId()));
          var housematePreference =
              preferences.get(new LanguagePreferenceLoaderKey(housemate, episode.getId()));
          assertThat(viewerPreference.audioLanguages()).containsExactly(Language.JA);
          assertThat(viewerPreference.audioSource()).isEqualTo(PreferenceSource.SERIES);
          assertThat(housematePreference.audioLanguages()).isEmpty();
        });
  }

  private List<Episode> seasonOfEpisodes(int count) {
    var series = seriesRepository.save(buildSeries("Series"));
    var season = buildSeason("Season 1", 1);
    season.setSeries(series);
    seasons.save(season);
    return IntStream.rangeClosed(1, count)
        .mapToObj(
            number -> {
              var episode = buildEpisode("Episode " + number, number);
              episode.setSeason(season);
              return episodes.save(episode);
            })
        .toList();
  }
}
