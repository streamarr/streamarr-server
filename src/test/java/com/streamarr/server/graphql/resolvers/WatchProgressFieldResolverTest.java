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
import com.streamarr.server.graphql.dataloaders.SessionProgressDataLoader;
import com.streamarr.server.graphql.dataloaders.WatchStatusDataLoader;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
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
      WatchStatusDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
      WatchProgressFieldResolverTest.TestConfig.class
    })
@DisplayName("Watch Progress Field Resolver Tests")
class WatchProgressFieldResolverTest {

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
      movie.getFiles().add(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(USER_ID, mediaFile.getId())
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
      movie.getFiles().add(mediaFile);

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

      sessionProgressRepository.save(
          progressBuilder(USER_ID, mediaFile.getId()).positionSeconds(300).build());

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
      graph.episode().getFiles().add(mediaFile);

      sessionProgressRepository.save(
          progressBuilder(USER_ID, mediaFile.getId())
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
          progressBuilder(USER_ID, mediaFile.getId()).positionSeconds(1200).build());

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
          progressBuilder(USER_ID, mediaFile.getId()).positionSeconds(300).build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { watchStatus } } }", graph.series().getId()),
              "data.series.seasons[0].watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
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
          progressBuilder(USER_ID, mediaFile.getId()).positionSeconds(300).build());

      // IN_PROGRESS requires the resolver to wire SERIES scope; a misrouted scope
      // would fall back to UNWATCHED
      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { watchStatus } }", series.getId()),
              "data.series.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
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
