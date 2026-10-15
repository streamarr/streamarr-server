package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Scans a library with far more files than database connections. TMDB answers after a delay, so
 * every file that a scan starts at once waits for a connection at the same moment, and each movie
 * with its credits holds a connection for tens of statements.
 */
@Isolated
@Tag("IntegrationTest")
@DisplayName("Large movie library scan")
@TestPropertySource(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=4",
      // HikariCP replaces a timeout below 250 ms with its 30 s default.
      "spring.datasource.hikari.connection-timeout=1000",
      "tmdb.api.requests-per-second=10000",
      // Artwork threads share the four connections and are not admitted.
      "artwork.primary-threads=2",
      "artwork.background-threads=1"
    })
class LargeMovieLibraryScanIT extends AbstractScanningIntegrationTest {

  private static final int FILES = 200;
  private static final int CREDITS_PER_MOVIE = 40;
  private static final int FIRST_TMDB_ID = 7_000_000;
  private static final int FIRST_PERSON_ID = 70_000_000;
  private static final Duration TMDB_DELAY = Duration.ofMillis(200);
  private static final Duration SCAN_BOUND = Duration.ofSeconds(90);

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private MeterRegistry meterRegistry;

  @TempDir Path tempDir;

  private ProbeSchedulerStarter.StartedProbeScheduler probeScheduler;

  @BeforeEach
  void cleanupDatabase() {
    wireMock.resetAll();
    deleteScannedRows();
  }

  @AfterEach
  void stopProbeSchedulerAndCleanup() {
    if (probeScheduler != null) {
      probeScheduler.scheduler().stop();
    }

    deleteScannedRows();
  }

  @Test
  @DisplayName(
      "Should match every file and save every requested image without a connection timeout when"
          + " scanning 200 files through four connections")
  void
      shouldMatchEveryFileAndSaveEveryRequestedImageWithoutAConnectionTimeoutWhenScanning200FilesThroughFourConnections()
          throws Exception {
    var library = movieLibraryWithFiles();
    stubTmdb();
    startProbeScheduler();
    var connectionTimeouts = meterRegistry.find("hikaricp.connections.timeout").counter();
    assertThat(connectionTimeouts).as("HikariCP connection timeout meter").isNotNull();
    var timeoutsBefore = connectionTimeouts.count();

    BoundedTask.runWithin(SCAN_BOUND, () -> libraryManagementService.scanLibrary(library.getId()));

    assertThat(connectionTimeouts.count()).isEqualTo(timeoutsBefore);
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .hasSize(FILES)
        .extracting(MediaFile::getStatus)
        .containsOnly(MediaFileStatus.MATCHED);
    assertThat(savedMovieImages()).isEqualTo(FILES * 2);
    assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.HEALTHY);
  }

  private Library movieLibraryWithFiles() throws IOException {
    for (var index = 0; index < FILES; index++) {
      var title = titleOf(index);
      var folder = Files.createDirectories(tempDir.resolve(title + " (2001)"));
      Files.createFile(folder.resolve(title + " (2001).mkv"));
    }

    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Large Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(tempDir))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  private static final List<String> TITLE_ADJECTIVES =
      List.of(
          "Amber", "Brisk", "Calm", "Dusky", "Eager", "Faint", "Gentle", "Hollow", "Ivory", "Jolly",
          "Keen", "Lunar", "Mellow", "Nimble", "Olive", "Placid", "Quiet", "Rustic", "Silver",
          "Tidal");
  private static final List<String> TITLE_NOUNS =
      List.of(
          "Harbor", "Meadow", "Canyon", "Lantern", "Orchard", "Glacier", "Willow", "Summit",
          "Prairie", "Thicket");

  // Plain words keep the filename parser from reading part of a title as a year or release tag.
  private static String titleOf(int index) {
    return TITLE_ADJECTIVES.get(index / TITLE_NOUNS.size())
        + " "
        + TITLE_NOUNS.get(index % TITLE_NOUNS.size());
  }

  private void stubTmdb() {
    for (var index = 0; index < FILES; index++) {
      stubSearch(titleOf(index), FIRST_TMDB_ID + index);
      stubMovie(titleOf(index), FIRST_TMDB_ID + index, index);
    }

    stubImage("/large-scan-poster.jpg");
    stubImage("/large-scan-backdrop.jpg");
  }

  private void stubSearch(String title, int tmdbId) {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo(title))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay((int) TMDB_DELAY.toMillis())
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "page": 1,
                          "results": [
                            {
                              "id": %d,
                              "title": "%s",
                              "original_title": "%s",
                              "release_date": "2001-01-01",
                              "popularity": 100.0,
                              "vote_count": 1000,
                              "vote_average": 8.5
                            }
                          ],
                          "total_results": 1,
                          "total_pages": 1
                        }
                        """
                            .formatted(tmdbId, title, title))));
  }

  private void stubMovie(String title, int tmdbId, int index) {
    var cast =
        IntStream.range(0, CREDITS_PER_MOVIE)
            .mapToObj(
                order ->
                    """
                    {"id": %d, "name": "Performer %d", "character": "Role %d", "order": %d}"""
                        .formatted(
                            FIRST_PERSON_ID + index * CREDITS_PER_MOVIE + order,
                            index * CREDITS_PER_MOVIE + order,
                            order,
                            order))
            .collect(Collectors.joining(","));
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/" + tmdbId))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay((int) TMDB_DELAY.toMillis())
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": %d,
                          "title": "%s",
                          "original_title": "%s",
                          "release_date": "2001-01-01",
                          "overview": "A feature in a large library.",
                          "runtime": 100,
                          "poster_path": "/large-scan-poster.jpg",
                          "backdrop_path": "/large-scan-backdrop.jpg",
                          "genres": [{"id": 28, "name": "Action"}],
                          "production_companies": [],
                          "credits": {"id": %d, "cast": [%s], "crew": []},
                          "release_dates": {"results": []}
                        }
                        """
                            .formatted(tmdbId, title, title, tmdbId, cast))));
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

  // Probes run in db-scheduler, and a scan finishes only after its probes have stored results.
  private void startProbeScheduler() {
    jdbcTemplate.update("DELETE FROM scheduled_tasks");
    probeScheduler =
        ProbeSchedulerStarter.fromContext(applicationContext)
            .start(probeExecution, new AbstractSchedulerListener() {}, Clock.systemUTC());
  }

  private int savedMovieImages() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM item_result r JOIN movie m ON m.id = r.item_id
        WHERE r.step = 'ARTWORK' AND r.outcome = 'SUCCEEDED'
          AND r.image_type IN ('POSTER', 'BACKDROP')
        """,
        Integer.class);
  }

  private void deleteScannedRows() {
    jdbcTemplate.update("DELETE FROM primary_artwork_scheduled_tasks");
    jdbcTemplate.update("DELETE FROM background_artwork_scheduled_tasks");
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
  }
}
