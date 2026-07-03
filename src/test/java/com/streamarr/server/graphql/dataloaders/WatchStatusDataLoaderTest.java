package com.streamarr.server.graphql.dataloaders;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildMatchedMediaFile;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Status DataLoader Tests")
class WatchStatusDataLoaderTest {

  private FakeSessionProgressRepository sessionProgressRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private WatchStatusDataLoader dataLoader;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
            episodeRepository,
            seasonRepository,
            new CapturingEventPublisher());
    dataLoader = new WatchStatusDataLoader(service);
  }

  @Test
  @DisplayName("Should return unwatched when entity has no media files")
  void shouldReturnUnwatchedWhenEntityHasNoMediaFiles() throws Exception {
    var key = new WatchStatusLoaderKey(UUID.randomUUID(), CollectableScope.DIRECT_MEDIA);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.UNWATCHED);
  }

  @Test
  @DisplayName("Should route each scope to its hierarchy when keys span all scopes")
  void shouldRouteEachScopeToItsHierarchyWhenKeysSpanAllScopes() throws Exception {
    var movie = buildMovie();
    var movieFile = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
    sessionProgressRepository.save(
        progressBuilder(USER_ID, movieFile.getId()).positionSeconds(300).build());

    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var seasonEpisode =
        episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    var seasonFile = mediaFileRepository.save(buildMatchedMediaFile(seasonEpisode.getId()));
    sessionProgressRepository.save(
        progressBuilder(USER_ID, seasonFile.getId()).positionSeconds(300).build());

    var series = buildSeries();
    var seriesSeason =
        seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
    var seriesEpisode =
        episodeRepository.save(Episode.builder().episodeNumber(1).season(seriesSeason).build());
    var seriesFile = mediaFileRepository.save(buildMatchedMediaFile(seriesEpisode.getId()));
    sessionProgressRepository.save(
        progressBuilder(USER_ID, seriesFile.getId()).positionSeconds(300).build());

    var movieKey = new WatchStatusLoaderKey(movie.getId(), CollectableScope.DIRECT_MEDIA);
    var seasonKey = new WatchStatusLoaderKey(season.getId(), CollectableScope.SEASON);
    var seriesKey = new WatchStatusLoaderKey(series.getId(), CollectableScope.SERIES);

    var result =
        dataLoader.load(Set.of(movieKey, seasonKey, seriesKey)).toCompletableFuture().get();

    // IN_PROGRESS is only reachable through the correct hierarchy for each scope;
    // a misrouted scope would fall back to UNWATCHED
    assertThat(result)
        .hasSize(3)
        .containsEntry(movieKey, WatchStatus.IN_PROGRESS)
        .containsEntry(seasonKey, WatchStatus.IN_PROGRESS)
        .containsEntry(seriesKey, WatchStatus.IN_PROGRESS);
  }
}
