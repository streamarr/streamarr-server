package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.LibraryOperationDriver;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Isolated
@Tag("IntegrationTest")
@DisplayName("Large series library scan")
@TestPropertySource(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=4",
      "spring.datasource.hikari.connection-timeout=1000",
      "tmdb.api.requests-per-second=10000",
      "artwork.scheduling.primary-threads=2",
      "artwork.scheduling.background-threads=1"
    })
class LargeSeriesLibraryScanIT extends AbstractScanningIntegrationTest {

  private static final List<String> SHOW_TITLES =
      List.of(
          "Amber Harbor",
          "Brisk Meadow",
          "Calm Canyon",
          "Dusky Lantern",
          "Eager Orchard",
          "Faint Glacier",
          "Gentle Willow",
          "Hollow Summit",
          "Ivory Prairie",
          "Jolly Thicket");
  private static final int SEASONS_PER_SHOW = 2;
  private static final int EPISODES_PER_SEASON = 10;
  private static final int CREDITS_PER_SHOW = 40;
  private static final int FIRST_TMDB_ID = 8_000_000;
  private static final int FIRST_PERSON_ID = 80_000_000;
  private static final Duration TMDB_DELAY = Duration.ofMillis(200);
  private static final Duration SCAN_BOUND = Duration.ofSeconds(90);

  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private ItemResultRepository itemResultRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @TempDir Path tempDir;

  @BeforeEach
  void cleanupDatabase() {
    wireMock.resetAll();
    deleteScannedRows();
  }

  @AfterEach
  void cleanupScannedRows() {
    stopStartedProbeScheduler();
    deleteScannedRows();
  }

  @Test
  @DisplayName(
      "Should match every episode to its show and season and save every requested image without a"
          + " connection timeout when scanning 200 files through four connections")
  void
      shouldMatchEveryEpisodeToItsShowAndSeasonAndSaveEveryRequestedImageWithoutAConnectionTimeoutWhenScanning200FilesThroughFourConnections()
          throws IOException {
    var library = seriesLibraryWithFiles();
    stubTmdb();
    startProbeScheduler();
    var connectionTimeouts = meterRegistry.find("hikaricp.connections.timeout").counter();
    assertThat(connectionTimeouts).as("HikariCP connection timeout meter").isNotNull();
    var timeoutsBefore = connectionTimeouts.count();

    libraryOperationDriver.scanToEnd(library.getId(), SCAN_BOUND);

    assertThat(connectionTimeouts.count()).isEqualTo(timeoutsBefore);
    var files = mediaFileRepository.findByLibraryId(library.getId());
    assertThat(files)
        .hasSize(200)
        .extracting(MediaFile::getStatus)
        .containsOnly(MediaFileStatus.MATCHED);
    var episodeIdsByFilepath =
        files.stream().collect(Collectors.toMap(MediaFile::getFilepathUri, MediaFile::getMediaId));
    var shows = seriesRepository.findByLibrary_Id(library.getId());
    assertThat(shows).extracting(Series::getTitle).containsExactlyInAnyOrderElementsOf(SHOW_TITLES);
    shows.forEach(show -> assertSeriesMatchedWithArtwork(show, episodeIdsByFilepath));
    assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  private void assertSeriesMatchedWithArtwork(Series show, Map<String, UUID> episodeIdsByFilepath) {
    assertArtworkSaved(show.getId(), ImageEntityType.SERIES, ImageType.POSTER, ImageType.BACKDROP);
    var seasons = seasonRepository.findBySeriesIdOrderBySeasonNumber(show.getId());
    assertThat(seasons)
        .as("seasons of %s", show.getTitle())
        .extracting(Season::getSeasonNumber)
        .containsExactly(1, 2);
    seasons.forEach(
        season -> assertSeasonMatchedWithArtwork(show.getTitle(), season, episodeIdsByFilepath));
  }

  private void assertSeasonMatchedWithArtwork(
      String title, Season season, Map<String, UUID> episodeIdsByFilepath) {
    assertArtworkSaved(season.getId(), ImageEntityType.SEASON, ImageType.POSTER);
    var episodes = episodeRepository.findBySeasonIdOrderByEpisodeNumber(season.getId());
    assertThat(episodes)
        .as("episodes of %s season %s", title, season.getSeasonNumber())
        .extracting(Episode::getEpisodeNumber)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    for (var episode : episodes) {
      var filepath = episodeFile(title, season.getSeasonNumber(), episode.getEpisodeNumber());
      assertThat(episodeIdsByFilepath)
          .containsEntry(FilepathCodec.encode(filepath), episode.getId());
      assertArtworkSaved(episode.getId(), ImageEntityType.EPISODE, ImageType.STILL);
    }
  }

  private void assertArtworkSaved(UUID itemId, ImageEntityType itemType, ImageType... imageTypes) {
    var results =
        itemResultRepository.findByItem(itemId, itemType).stream()
            .filter(result -> result.step() == ItemStep.ARTWORK)
            .toList();
    assertThat(results)
        .as("artwork of %s %s", itemType, itemId)
        .extracting(ItemResult::imageType)
        .containsExactlyInAnyOrder(imageTypes);
    assertThat(results).extracting(ItemResult::outcome).containsOnly(new ItemOutcome.Succeeded());
  }

  private Library seriesLibraryWithFiles() throws IOException {
    for (var title : SHOW_TITLES) {
      createShowFiles(title);
    }

    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Large Series")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(tempDir))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.SERIES)
            .build());
  }

  private void createShowFiles(String title) throws IOException {
    for (var season = 1; season <= SEASONS_PER_SHOW; season++) {
      createSeasonFiles(title, season);
    }
  }

  private void createSeasonFiles(String title, int season) throws IOException {
    Files.createDirectories(episodeFile(title, season, 1).getParent());
    for (var episode = 1; episode <= EPISODES_PER_SEASON; episode++) {
      Files.createFile(episodeFile(title, season, episode));
    }
  }

  private Path episodeFile(String title, int season, int episode) {
    return tempDir
        .resolve(title)
        .resolve("Season %02d".formatted(season))
        .resolve("%s.S%02dE%02d.mkv".formatted(title, season, episode));
  }

  private void stubTmdb() {
    for (var index = 0; index < SHOW_TITLES.size(); index++) {
      stubShow(index);
    }

    stubImage("/large-series-poster.jpg");
    stubImage("/large-series-backdrop.jpg");
    stubImage("/large-season-poster.jpg");
    stubImage("/large-episode-still.jpg");
  }

  private void stubShow(int index) {
    var title = SHOW_TITLES.get(index);
    var tmdbId = FIRST_TMDB_ID + index;
    stubJson(
        get(urlPathEqualTo("/search/tv")).withQueryParam("query", equalTo(title)),
        """
        {
          "page": 1,
          "results": [{"id": %d, "name": "%s", "original_name": "%s",
            "first_air_date": "2001-01-01", "popularity": 100.0,
            "vote_count": 1000, "vote_average": 8.5}],
          "total_results": 1, "total_pages": 1
        }
        """
            .formatted(tmdbId, title, title));
    stubShowMetadata(index);
    for (var season = 1; season <= SEASONS_PER_SHOW; season++) {
      stubSeason(tmdbId, season);
    }
  }

  private void stubShowMetadata(int index) {
    var title = SHOW_TITLES.get(index);
    var tmdbId = FIRST_TMDB_ID + index;
    var cast =
        IntStream.range(0, CREDITS_PER_SHOW)
            .mapToObj(
                order ->
                    """
            {"id": %d, "name": "Performer %d", "character": "Role %d", "order": %d}"""
                        .formatted(
                            FIRST_PERSON_ID + index * CREDITS_PER_SHOW + order,
                            index * CREDITS_PER_SHOW + order,
                            order,
                            order))
            .collect(Collectors.joining(","));
    stubJson(
        get(urlPathEqualTo("/tv/" + tmdbId))
            .withQueryParam("append_to_response", equalTo("content_ratings,credits,external_ids")),
        """
        {
          "id": %d, "name": "%s", "original_name": "%s", "first_air_date": "2001-01-01",
          "overview": "A show in a large library.", "episode_run_time": [45],
          "poster_path": "/large-series-poster.jpg", "backdrop_path": "/large-series-backdrop.jpg",
          "genres": [{"id": 18, "name": "Drama"}], "production_companies": [],
          "credits": {"id": %d, "cast": [%s], "crew": []},
          "content_ratings": {"results": []}, "external_ids": {},
          "seasons": [{"id": %d, "name": "Season 1", "season_number": 1,
                       "air_date": "2001-01-01", "episode_count": 10},
                      {"id": %d, "name": "Season 2", "season_number": 2,
                       "air_date": "2002-01-01", "episode_count": 10}]
        }
        """
            .formatted(tmdbId, title, title, tmdbId, cast, tmdbId * 10 + 1, tmdbId * 10 + 2));
  }

  private void stubSeason(int tmdbId, int season) {
    var episodes =
        IntStream.rangeClosed(1, EPISODES_PER_SEASON)
            .mapToObj(
                episode ->
                    """
            {"id": %d, "episode_number": %d, "season_number": %d, "name": "Episode %d",
             "overview": "An episode in a large library.", "air_date": "2001-01-01",
             "runtime": 45, "still_path": "/large-episode-still.jpg"}
            """
                        .formatted(tmdbId * 100 + season * 10 + episode, episode, season, episode))
            .collect(Collectors.joining(","));
    stubJson(
        get(urlPathEqualTo("/tv/" + tmdbId + "/season/" + season)),
        """
        {
          "id": %d, "name": "Season %d", "season_number": %d,
          "overview": "A season in a large library.", "air_date": "2001-01-01",
          "poster_path": "/large-season-poster.jpg", "episodes": [%s]
        }
        """
            .formatted(tmdbId * 10 + season, season, season, episodes));
  }

  private void stubJson(MappingBuilder request, String body) {
    wireMock.stubFor(
        request.willReturn(
            aResponse()
                .withStatus(200)
                .withFixedDelay((int) TMDB_DELAY.toMillis())
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
  }

  private void stubImage(String path) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
  }

  private void deleteScannedRows() {
    jdbcTemplate.update("DELETE FROM primary_artwork_scheduled_tasks");
    jdbcTemplate.update("DELETE FROM background_artwork_scheduled_tasks");
    mediaFileRepository.deleteAll();
    episodeRepository.deleteAll();
    seasonRepository.deleteAll();
    seriesRepository.deleteAll();
    libraryRepository.deleteAll();
  }
}
