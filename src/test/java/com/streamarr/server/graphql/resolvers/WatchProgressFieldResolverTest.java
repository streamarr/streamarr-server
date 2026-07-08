package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.graphql.dataloaders.AggregateWatchProgressDataLoader;
import com.streamarr.server.graphql.dataloaders.SessionProgressDataLoader;
import com.streamarr.server.graphql.dataloaders.WatchStatusDataLoader;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.time.Instant;
import java.util.List;
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
@SpringBootTest(
    classes = {
      WatchProgressFieldResolver.class,
      SessionProgressDataLoader.class,
      AggregateWatchProgressDataLoader.class,
      WatchStatusDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
      EpisodeFieldResolver.class,
      WatchProgressFieldResolverTest.TestConfig.class
    })
@DisplayName("Watch Progress Field Resolver Tests")
class WatchProgressFieldResolverTest {

  private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private FakeSessionProgressRepository sessionProgressRepository;
  @Autowired private FakeMediaFileRepository mediaFileRepository;
  @Autowired private FakeEpisodeRepository episodeRepository;
  @Autowired private FakeSeasonRepository seasonRepository;

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
    FakeWatchHistoryRepository fakeWatchHistoryRepository() {
      return new FakeWatchHistoryRepository();
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
        CapturingEventPublisher eventPublisher) {
      return new WatchStatusService(
          sessionProgressRepository,
          watchHistoryRepository,
          mediaFileRepository,
          episodeRepository,
          seasonRepository,
          eventPublisher);
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
      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of(mediaFile));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId())
              .positionSeconds(1800)
              .percentComplete(50.0)
              .durationSeconds(3600)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds percentComplete durationSeconds } } }",
                  movie.getId()));

      assertThat(context.<Integer>read("data.movie.watchProgress.positionSeconds")).isEqualTo(1800);
      assertThat(context.<Double>read("data.movie.watchProgress.percentComplete")).isEqualTo(50.0);
      assertThat(context.<Integer>read("data.movie.watchProgress.durationSeconds")).isEqualTo(3600);
    }

    @Test
    @DisplayName("Should return null watch progress when movie has no files")
    void shouldReturnNullWatchProgressWhenMovieHasNoFiles() {
      var movie = setupMovie();
      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of());

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds } } }", movie.getId()),
              "data.movie.watchProgress");

      assertThat(watchProgress).isNull();
    }

    @Test
    @DisplayName("Should return null watch progress when no progress exists")
    void shouldReturnNullWatchProgressWhenNoProgressExists() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);
      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of(mediaFile));

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds } } }", movie.getId()),
              "data.movie.watchProgress");

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
      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of(mediaFile));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(300).build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ movie(id: \"%s\") { watchStatus } }", movie.getId()),
              "data.movie.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
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
      when(seriesService.findMediaFiles(graph.episode().getId())).thenReturn(List.of(mediaFile));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { watchProgress { positionSeconds percentComplete durationSeconds } } } } }",
                  graph.series().getId()));

      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].watchProgress.positionSeconds"))
          .isEqualTo(600);
      assertThat(
              context.<Double>read(
                  "data.series.seasons[0].episodes[0].watchProgress.percentComplete"))
          .isEqualTo(25.0);
      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].watchProgress.durationSeconds"))
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
                  "{ series(id: \"%s\") { seasons { episodes { watchStatus } } } }",
                  graph.series().getId()),
              "data.series.seasons[0].episodes[0].watchStatus");

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
                  "{ series(id: \"%s\") { seasons { watchStatus } } }", graph.series().getId()),
              "data.series.seasons[0].watchStatus");

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
                  "{ series(id: \"%s\") { seasons { watchProgress { positionSeconds percentComplete durationSeconds } } } }",
                  seriesId));

      assertThat(context.<Integer>read("data.series.seasons[0].watchProgress.positionSeconds"))
          .isEqualTo(900);
      assertThat(context.<Double>read("data.series.seasons[0].watchProgress.percentComplete"))
          .isEqualTo(75.0);
      assertThat(context.<Integer>read("data.series.seasons[0].watchProgress.durationSeconds"))
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
                  "{ series(id: \"%s\") { seasons { watchProgress { positionSeconds } } } }",
                  seriesId),
              "data.series.seasons[0].watchProgress");

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
              String.format("{ series(id: \"%s\") { watchStatus } }", series.getId()),
              "data.series.watchStatus");

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
                  "{ series(id: \"%s\") { watchProgress { positionSeconds percentComplete durationSeconds } } }",
                  series.getId()));

      assertThat(context.<Integer>read("data.series.watchProgress.positionSeconds")).isEqualTo(900);
      assertThat(context.<Double>read("data.series.watchProgress.percentComplete")).isEqualTo(25.0);
      assertThat(context.<Integer>read("data.series.watchProgress.durationSeconds"))
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
                  "{ series(id: \"%s\") { watchProgress { positionSeconds } } }", seriesId),
              "data.series.watchProgress");

      assertThat(watchProgress).isNull();
    }
  }

  @Nested
  @DisplayName("Season-Series Navigation")
  class SeasonSeriesNavigationTests {

    @Test
    @DisplayName("Should resolve series from season")
    void shouldResolveSeriesFromSeason() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Parent Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
      season.setId(seasonId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      String seriesTitle =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { seasons { series { title } } } }", seriesId),
              "data.series.seasons[0].series.title");

      assertThat(seriesTitle).isEqualTo("Parent Series");
    }

    @Test
    @DisplayName("Should resolve season from episode")
    void shouldResolveSeasonFromEpisode() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
      season.setId(seasonId);

      var episodeId = UUID.randomUUID();
      var episode = Episode.builder().title("Pilot").episodeNumber(1).season(season).build();
      episode.setId(episodeId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));
      when(seriesService.findSeasonById(seasonId)).thenReturn(Optional.of(season));

      Integer seasonNumber =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { season { seasonNumber } } } } }",
                  seriesId),
              "data.series.seasons[0].episodes[0].season.seasonNumber");

      assertThat(seasonNumber).isEqualTo(1);
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
