package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.graphql.dataloaders.LastWatchedAtDataLoader;
import com.streamarr.server.graphql.dataloaders.WatchProgressDataLoader;
import com.streamarr.server.graphql.dataloaders.WatchStatusDataLoader;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.security.TestIdentityConstants;
import com.streamarr.server.support.security.WithProfileContext;
import graphql.GraphQLError;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      WatchProgressFieldResolver.class,
      SelectedProfileStateResolver.class,
      WatchProgressDataLoader.class,
      WatchStatusDataLoader.class,
      LastWatchedAtDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
      EpisodeFieldResolver.class,
      WatchProgressFieldResolverTest.TestConfig.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Watch Progress Field Resolver Tests")
class WatchProgressFieldResolverTest {

  private static final UUID PROFILE_ID = TestIdentityConstants.PROFILE_ID;
  private static final UUID OTHER_PROFILE_ID = UUID.randomUUID();

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private FakeSessionProgressRepository sessionProgressRepository;
  @Autowired private FakeMediaFileRepository mediaFileRepository;
  @Autowired private FakeEpisodeRepository episodeRepository;
  @Autowired private FakeSeasonRepository seasonRepository;

  @MockitoBean private UserAccountRepository userAccountRepository;
  @MockitoBean private MovieService movieService;
  @MockitoBean private SeriesService seriesService;

  @TestConfiguration
  static class TestConfig {

    @Bean
    FakeSessionProgressRepository fakeSessionProgressRepository() {
      return new FakeSessionProgressRepository();
    }

    @Bean
    FakeMediaFileRepository fakeMediaFileRepository() {
      return new FakeMediaFileRepository();
    }

    @Bean
    FakeEpisodeRepository fakeEpisodeRepository() {
      return new FakeEpisodeRepository();
    }

    @Bean
    FakeSeasonRepository fakeSeasonRepository() {
      return new FakeSeasonRepository();
    }

    @Bean
    FakeSeriesRepository fakeSeriesRepository() {
      return new FakeSeriesRepository();
    }

    @Bean
    FakeWatchHistoryRepository watchHistoryWithUnreadableLastWatchedTimes() {
      return new FakeWatchHistoryRepository() {
        @Override
        public Map<UUID, Instant> findLastWatchedByItemIds(
            UUID profileId, CollectableScope scope, Collection<UUID> itemIds) {
          throw new IllegalStateException("Last-watched times are unreadable");
        }
      };
    }

    @Bean
    CapturingEventPublisher capturingEventPublisher() {
      return new CapturingEventPublisher();
    }

    @Bean
    WatchStatusService watchStatusService(
        FakeSessionProgressRepository sessionProgressRepository,
        FakeWatchHistoryRepository watchHistoryRepository,
        FakeMediaFileRepository mediaFileRepository,
        FakeEpisodeRepository episodeRepository,
        FakeSeasonRepository seasonRepository,
        FakeSeriesRepository seriesRepository,
        CapturingEventPublisher eventPublisher) {
      return new WatchStatusService(
          sessionProgressRepository,
          watchHistoryRepository,
          mediaFileRepository,
          new FakeMovieRepository(),
          episodeRepository,
          seasonRepository,
          seriesRepository,
          eventPublisher,
          new FakeAuthorizationService(defaultIdentityBuilder().build()));
    }
  }

  @BeforeEach
  void setUp() {
    sessionProgressRepository.deleteAll();
    mediaFileRepository.deleteAll();
    episodeRepository.deleteAll();
    seasonRepository.deleteAll();
  }

  @Nested
  @DisplayName("Movie Watch Progress")
  class MovieWatchProgress {

    @Test
    @DisplayName("Should return watch progress when movie has media files")
    void shouldReturnWatchProgressWhenMovieHasMediaFiles() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId())
              .positionSeconds(1800)
              .percentComplete(50.0)
              .durationSeconds(3600)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ movie(id: \"%s\") { selectedProfileState { watchState { watchProgress { positionSeconds percentComplete durationSeconds } } } } }",
                  movie.getId()));

      assertThat(
              context.<Integer>read(
                  "data.movie.selectedProfileState.watchState.watchProgress.positionSeconds"))
          .isEqualTo(1800);
      assertThat(
              context.<Double>read(
                  "data.movie.selectedProfileState.watchState.watchProgress.percentComplete"))
          .isEqualTo(50.0);
      assertThat(
              context.<Integer>read(
                  "data.movie.selectedProfileState.watchState.watchProgress.durationSeconds"))
          .isEqualTo(3600);
    }

    @Test
    @DisplayName("Should return null watch progress when movie has no files")
    void shouldReturnNullWatchProgressWhenMovieHasNoFiles() {
      var movie = setupMovie();

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { selectedProfileState { watchState { watchProgress { positionSeconds } } } } }",
                  movie.getId()),
              "data.movie.selectedProfileState.watchState.watchProgress");

      assertThat(watchProgress).isNull();
    }

    @Test
    @DisplayName("Should return null watch progress when no progress exists")
    void shouldReturnNullWatchProgressWhenNoProgressExists() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { selectedProfileState { watchState { watchProgress { positionSeconds } } } } }",
                  movie.getId()),
              "data.movie.selectedProfileState.watchState.watchProgress");

      assertThat(watchProgress).isNull();
    }
  }

  @Nested
  @DisplayName("Movie Watch Status")
  class MovieWatchStatusTests {

    @Test
    @DisplayName("Should return in progress when movie has partial progress")
    void shouldReturnInProgressWhenMovieHasPartialProgress() {
      var movie = setupMovie();

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { selectedProfileState { watchState { watchStatus } } } }",
                  movie.getId()),
              "data.movie.selectedProfileState.watchState.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Selected Profile Watch State")
  class SelectedProfileWatchState {

    @Test
    @DisplayName(
        "Should show only the selected Profile's progress when another Profile has progress on the"
            + " same movies")
    void shouldShowOnlyTheSelectedProfilesProgressWhenAnotherProfileHasProgressOnTheSameMovies() {
      var movies = List.of(setupMovie(), setupMovie(), setupMovie());
      var fileIds =
          movies.stream()
              .map(
                  movie -> {
                    var mediaFile = buildMediaFile();
                    mediaFile.setMediaId(movie.getId());
                    var fileId = mediaFileRepository.save(mediaFile).getId();
                    sessionProgressRepository.save(
                        progressBuilder(OTHER_PROFILE_ID, fileId).positionSeconds(1200).build());
                    return fileId;
                  })
              .toList();
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, fileIds.getFirst()).positionSeconds(300).build());

      var result =
          dgsQueryExecutor.execute(
              """
              {
                started: movie(id: "%s") { ...state }
                second: movie(id: "%s") { ...state }
                third: movie(id: "%s") { ...state }
              }
              fragment state on Movie {
                selectedProfileState { watchState { watchStatus watchProgress { positionSeconds } } }
              }
              """
                  .formatted(movies.get(0).getId(), movies.get(1).getId(), movies.get(2).getId()));

      assertThat(result.getErrors()).isEmpty();
      var data = JsonPath.parse(result.<Map<String, Object>>getData());
      assertThat(data.<Map<String, Object>>read("started.selectedProfileState.watchState"))
          .isEqualTo(
              Map.of(
                  "watchStatus", "IN_PROGRESS", "watchProgress", Map.of("positionSeconds", 300)));
      assertThat(List.of("second", "third"))
          .allSatisfy(
              alias ->
                  assertThat(
                          data.<Map<String, Object>>read(
                              alias + ".selectedProfileState.watchState"))
                      .containsEntry("watchStatus", "UNWATCHED")
                      .containsEntry("watchProgress", null));
    }

    @Test
    @DisplayName(
        "Should keep the selected Profile state and null only the failed field when a nullable"
            + " field under it fails")
    void shouldKeepTheSelectedProfileStateAndNullOnlyTheFailedFieldWhenANullableFieldFails() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  "{ movie(id: \"%s\") { selectedProfileState { watchState { watchStatus watchProgress { positionSeconds } lastWatchedAt } } } }",
                  movie.getId()));

      var data = JsonPath.parse(result.<Map<String, Object>>getData());
      assertThat(data.<Map<String, Object>>read("movie.selectedProfileState.watchState"))
          .containsEntry("watchStatus", "IN_PROGRESS")
          .containsEntry("watchProgress", Map.of("positionSeconds", 300))
          .containsEntry("lastWatchedAt", null);
      assertThat(result.getErrors())
          .singleElement()
          .extracting(GraphQLError::getPath)
          .isEqualTo(List.of("movie", "selectedProfileState", "watchState", "lastWatchedAt"));
    }
  }

  @Nested
  @DisplayName("Episode Watch Progress and Status")
  class EpisodeWatchProgressAndStatus {

    @Test
    @DisplayName("Should return watch progress when episode has media files")
    void shouldReturnWatchProgressWhenEpisodeHasMediaFiles() {
      var graph = setupSeriesGraph();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(graph.episode().getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { selectedProfileState { watchState { watchProgress { positionSeconds percentComplete durationSeconds } } } } } } }",
                  graph.series().getId()));

      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].selectedProfileState.watchState.watchProgress.positionSeconds"))
          .isEqualTo(600);
      assertThat(
              context.<Double>read(
                  "data.series.seasons[0].episodes[0].selectedProfileState.watchState.watchProgress.percentComplete"))
          .isEqualTo(25.0);
      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].selectedProfileState.watchState.watchProgress.durationSeconds"))
          .isEqualTo(2400);
    }

    @Test
    @DisplayName("Should return in progress when episode has active progress")
    void shouldReturnInProgressWhenEpisodeHasActiveProgress() {
      var graph = setupSeriesGraph();

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(graph.episode().getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(1200).build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { selectedProfileState { watchState { watchStatus } } } } } }",
                  graph.series().getId()),
              "data.series.seasons[0].episodes[0].selectedProfileState.watchState.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Season Watch Status")
  class SeasonWatchStatusTests {

    @Test
    @DisplayName("Should return in progress when season has partial progress")
    void shouldReturnInProgressWhenSeasonHasPartialProgress() {
      var graph = setupSeriesGraph();

      var episode =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(graph.season()).build());

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(episode.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { selectedProfileState { watchState { watchStatus } } } } }",
                  graph.series().getId()),
              "data.series.seasons[0].selectedProfileState.watchState.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Season Watch Progress")
  class SeasonWatchProgressTests {

    @Test
    @DisplayName(
        "Should return progress from most recently modified episode when season has multiple"
            + " progresses")
    void shouldReturnProgressFromMostRecentlyModifiedEpisodeWhenSeasonHasMultipleProgresses() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(UUID.randomUUID());

      var olderEpisode = Episode.builder().episodeNumber(1).season(season).build();
      olderEpisode.setId(UUID.randomUUID());
      episodeRepository.save(olderEpisode);

      var newerEpisode = Episode.builder().episodeNumber(2).season(season).build();
      newerEpisode.setId(UUID.randomUUID());
      episodeRepository.save(newerEpisode);

      var olderFile = buildMediaFile();
      olderFile.setMediaId(olderEpisode.getId());
      mediaFileRepository.save(olderFile);

      var newerFile = buildMediaFile();
      newerFile.setMediaId(newerEpisode.getId());
      mediaFileRepository.save(newerFile);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      var olderProgress =
          sessionProgressRepository.save(
              progressBuilder(PROFILE_ID, olderFile.getId())
                  .positionSeconds(300)
                  .percentComplete(10.0)
                  .durationSeconds(3000)
                  .build());
      AuditFieldSetter.setLastModifiedOn(olderProgress, Instant.parse("2026-01-01T00:00:00Z"));

      var newerProgress =
          sessionProgressRepository.save(
              progressBuilder(PROFILE_ID, newerFile.getId())
                  .positionSeconds(900)
                  .percentComplete(75.0)
                  .durationSeconds(1200)
                  .build());
      AuditFieldSetter.setLastModifiedOn(newerProgress, Instant.parse("2026-02-01T00:00:00Z"));

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ series(id: \"%s\") { seasons { selectedProfileState { watchState { watchProgress { positionSeconds percentComplete durationSeconds } } } } } }",
                  seriesId));

      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].selectedProfileState.watchState.watchProgress.positionSeconds"))
          .isEqualTo(900);
      assertThat(
              context.<Double>read(
                  "data.series.seasons[0].selectedProfileState.watchState.watchProgress.percentComplete"))
          .isEqualTo(75.0);
      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].selectedProfileState.watchState.watchProgress.durationSeconds"))
          .isEqualTo(1200);
    }

    @Test
    @DisplayName("Should return null watch progress when season has no episode progress")
    void shouldReturnNullWatchProgressWhenSeasonHasNoEpisodeProgress() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(UUID.randomUUID());

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { selectedProfileState { watchState { watchProgress { positionSeconds } } } } } }",
                  seriesId),
              "data.series.seasons[0].selectedProfileState.watchState.watchProgress");

      assertThat(watchProgress).isNull();
    }
  }

  @Nested
  @DisplayName("Series Watch Status")
  class SeriesWatchStatusTests {

    @Test
    @DisplayName("Should return in progress when series episode has active progress")
    void shouldReturnInProgressWhenSeriesEpisodeHasActiveProgress() {
      var series = buildSeries("Test Series");
      when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));

      var season = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var episode =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(episode.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

      // IN_PROGRESS requires the resolver to wire SERIES scope; a misrouted scope
      // would fall back to UNWATCHED
      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { selectedProfileState { watchState { watchStatus } } } }",
                  series.getId()),
              "data.series.selectedProfileState.watchState.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Series Watch Progress")
  class SeriesWatchProgressTests {

    @Test
    @DisplayName(
        "Should return progress from most recently modified episode when series spans multiple"
            + " seasons")
    void shouldReturnProgressFromMostRecentlyModifiedEpisodeWhenSeriesSpansMultipleSeasons() {
      var series = Series.builder().title("Test Series").build();
      series.setId(UUID.randomUUID());

      var seasonOne = Season.builder().seasonNumber(1).series(series).build();
      seasonOne.setId(UUID.randomUUID());
      seasonRepository.save(seasonOne);

      var seasonTwo = Season.builder().seasonNumber(2).series(series).build();
      seasonTwo.setId(UUID.randomUUID());
      seasonRepository.save(seasonTwo);

      var olderEpisode = Episode.builder().episodeNumber(1).season(seasonOne).build();
      olderEpisode.setId(UUID.randomUUID());
      episodeRepository.save(olderEpisode);

      var newerEpisode = Episode.builder().episodeNumber(1).season(seasonTwo).build();
      newerEpisode.setId(UUID.randomUUID());
      episodeRepository.save(newerEpisode);

      var olderFile = buildMediaFile();
      olderFile.setMediaId(olderEpisode.getId());
      mediaFileRepository.save(olderFile);

      var newerFile = buildMediaFile();
      newerFile.setMediaId(newerEpisode.getId());
      mediaFileRepository.save(newerFile);

      when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));

      var olderProgress =
          sessionProgressRepository.save(
              progressBuilder(PROFILE_ID, olderFile.getId())
                  .positionSeconds(300)
                  .percentComplete(10.0)
                  .durationSeconds(3000)
                  .build());
      AuditFieldSetter.setLastModifiedOn(olderProgress, Instant.parse("2026-01-01T00:00:00Z"));

      var newerProgress =
          sessionProgressRepository.save(
              progressBuilder(PROFILE_ID, newerFile.getId())
                  .positionSeconds(900)
                  .percentComplete(25.0)
                  .durationSeconds(3600)
                  .build());
      AuditFieldSetter.setLastModifiedOn(newerProgress, Instant.parse("2026-02-01T00:00:00Z"));

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ series(id: \"%s\") { selectedProfileState { watchState { watchProgress { positionSeconds percentComplete durationSeconds } } } } }",
                  series.getId()));

      assertThat(
              context.<Integer>read(
                  "data.series.selectedProfileState.watchState.watchProgress.positionSeconds"))
          .isEqualTo(900);
      assertThat(
              context.<Double>read(
                  "data.series.selectedProfileState.watchState.watchProgress.percentComplete"))
          .isEqualTo(25.0);
      assertThat(
              context.<Integer>read(
                  "data.series.selectedProfileState.watchState.watchProgress.durationSeconds"))
          .isEqualTo(3600);
    }

    @Test
    @DisplayName("Should return null watch progress when series has no episode progress")
    void shouldReturnNullWatchProgressWhenSeriesHasNoEpisodeProgress() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { selectedProfileState { watchState { watchProgress { positionSeconds } } } } }",
                  seriesId),
              "data.series.selectedProfileState.watchState.watchProgress");

      assertThat(watchProgress).isNull();
    }
  }

  private Movie setupMovie() {
    var movie = buildMovie("Test Movie");
    when(movieService.findById(movie.getId())).thenReturn(Optional.of(movie));
    return movie;
  }

  private SeriesGraph setupSeriesGraph() {
    var series = buildSeries("Test Series");
    var season = buildSeason("Season 1", 1);
    var episode = buildEpisode("Pilot", 1);
    when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));
    when(seriesService.findSeasons(series.getId())).thenReturn(List.of(season));
    when(seriesService.findEpisodes(season.getId())).thenReturn(List.of(episode));
    return new SeriesGraph(series, season, episode);
  }

  private record SeriesGraph(Series series, Season season, Episode episode) {}

  private MediaFile buildMediaFile() {
    var mediaFile =
        MediaFile.builder()
            .filename("test.mkv")
            .filepathUri("/media/test.mkv")
            .size(1_000_000)
            .build();
    mediaFile.setId(UUID.randomUUID());
    return mediaFile;
  }
}
