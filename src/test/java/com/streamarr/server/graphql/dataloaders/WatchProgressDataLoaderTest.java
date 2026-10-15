package com.streamarr.server.graphql.dataloaders;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildMatchedMediaFile;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.Set;
import java.util.UUID;
import org.dataloader.DataLoaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Progress DataLoader Tests")
class WatchProgressDataLoaderTest {

  private FakeSessionProgressRepository sessionProgressRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private WatchProgressDataLoader dataLoader;

  private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @BeforeEach
  void setUp() {
    sessionProgressRepository = new FakeSessionProgressRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    var service =
        new WatchStatusService(
            sessionProgressRepository,
            new FakeWatchHistoryRepository(),
            mediaFileRepository,
            new FakeMovieRepository(),
            episodeRepository,
            seasonRepository,
            new FakeSeriesRepository(),
            new CapturingEventPublisher());
    dataLoader = new WatchProgressDataLoader(service);
  }

  @Test
  @DisplayName("Should return each movie's progress when movies are batch loaded")
  void shouldReturnEachMoviesProgressWhenMoviesAreBatchLoaded() throws Exception {
    var first = buildMovie();
    var second = buildMovie();
    var firstFile = mediaFileRepository.save(buildMatchedMediaFile(first.getId()));
    var secondFile = mediaFileRepository.save(buildMatchedMediaFile(second.getId()));
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, firstFile.getId())
            .positionSeconds(300)
            .percentComplete(50.0)
            .durationSeconds(600)
            .build());
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, secondFile.getId())
            .positionSeconds(600)
            .percentComplete(75.0)
            .durationSeconds(800)
            .build());

    var firstKey =
        new WatchProgressLoaderKey(PROFILE_ID, first.getId(), CollectableScope.DIRECT_MEDIA);
    var secondKey =
        new WatchProgressLoaderKey(PROFILE_ID, second.getId(), CollectableScope.DIRECT_MEDIA);
    var result = dataLoader.load(Set.of(firstKey, secondKey)).toCompletableFuture().get();

    assertThat(result.get(firstKey).positionSeconds()).isEqualTo(300);
    assertThat(result.get(firstKey).percentComplete()).isEqualTo(50.0);
    assertThat(result.get(firstKey).durationSeconds()).isEqualTo(600);
    assertThat(result.get(secondKey).positionSeconds()).isEqualTo(600);
    assertThat(result.get(secondKey).percentComplete()).isEqualTo(75.0);
    assertThat(result.get(secondKey).durationSeconds()).isEqualTo(800);
  }

  @Test
  @DisplayName("Should scope progress to each profile when keys span multiple profiles")
  void shouldScopeProgressToEachProfileWhenKeysSpanMultipleProfiles() throws Exception {
    var movie = buildMovie();
    var mediaFile = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

    var mine = new WatchProgressLoaderKey(PROFILE_ID, movie.getId(), CollectableScope.DIRECT_MEDIA);
    var theirs =
        new WatchProgressLoaderKey(UUID.randomUUID(), movie.getId(), CollectableScope.DIRECT_MEDIA);

    var result = dataLoader.load(Set.of(mine, theirs)).toCompletableFuture().get();

    assertThat(result.get(mine).positionSeconds()).isEqualTo(300);
    assertThat(result.get(theirs)).isNull();
  }

  @Test
  @DisplayName("Should route each scope to its hierarchy when keys span all scopes")
  void shouldRouteEachScopeToItsHierarchyWhenKeysSpanAllScopes() throws Exception {
    var movie = buildMovie();
    var movieFile = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, movieFile.getId()).positionSeconds(100).build());

    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var seasonEpisode =
        episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    var seasonFile = mediaFileRepository.save(buildMatchedMediaFile(seasonEpisode.getId()));
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, seasonFile.getId()).positionSeconds(200).build());

    var series = buildSeries();
    var seriesSeason =
        seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
    var seriesEpisode =
        episodeRepository.save(Episode.builder().episodeNumber(1).season(seriesSeason).build());
    var seriesFile = mediaFileRepository.save(buildMatchedMediaFile(seriesEpisode.getId()));
    sessionProgressRepository.save(
        progressBuilder(PROFILE_ID, seriesFile.getId()).positionSeconds(300).build());

    var movieKey =
        new WatchProgressLoaderKey(PROFILE_ID, movie.getId(), CollectableScope.DIRECT_MEDIA);
    var seasonKey = new WatchProgressLoaderKey(PROFILE_ID, season.getId(), CollectableScope.SEASON);
    var seriesKey = new WatchProgressLoaderKey(PROFILE_ID, series.getId(), CollectableScope.SERIES);

    var result =
        dataLoader.load(Set.of(movieKey, seasonKey, seriesKey)).toCompletableFuture().get();

    assertThat(result.get(movieKey).positionSeconds()).isEqualTo(100);
    assertThat(result.get(seasonKey).positionSeconds()).isEqualTo(200);
    assertThat(result.get(seriesKey).positionSeconds()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should resolve null when movie has no progress")
  void shouldResolveNullWhenMovieHasNoProgress() throws Exception {
    var loader = DataLoaderFactory.newMappedDataLoader(dataLoader);

    var future =
        loader.load(
            new WatchProgressLoaderKey(
                PROFILE_ID, UUID.randomUUID(), CollectableScope.DIRECT_MEDIA));
    loader.dispatch();

    assertThat(future.get()).isNull();
  }
}
