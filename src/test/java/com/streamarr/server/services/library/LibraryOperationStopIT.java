package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.support.HeldResponses.held;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.concurrency.AdaptiveAdmission;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.BoundedTask;
import com.streamarr.server.support.LibraryOperationDriver;
import com.streamarr.server.support.LibraryOperationSchedulerStarter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stops a scan and a refresh, or shuts down the scheduler, while the metadata provider holds every
 * admitted task, then releases the provider. The probe scheduler stays stopped until a scan runs to
 * its end, so a stop or a shutdown that waited for probes would never end.
 */
@Tag("IntegrationTest")
@DisplayName("Library operation stop integration tests")
class LibraryOperationStopIT extends AbstractScanningIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final int FILES = 24;
  private static final int FIRST_TMDB_ID = 8_100_000;
  private static final String CAST_PERSON_SOURCE_ID = "8199001";
  private static final String POSTER_PATH = "/stop-poster.jpg";
  private static final String BACKDROP_PATH = "/stop-backdrop.jpg";
  private static final String PROFILE_PATH = "/stop-profile.jpg";

  private static final List<String> TITLES =
      List.of(
          "Amber", "Brisk", "Calm", "Dusky", "Eager", "Faint", "Gentle", "Hollow", "Ivory", "Jolly",
          "Keen", "Lunar", "Mellow", "Nimble", "Olive", "Placid", "Quiet", "Rustic", "Silver",
          "Tidal", "Umber", "Velvet", "Woven", "Zephyr");

  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private ProbeExecution probeExecution;

  @TempDir Path tempDir;

  private AuthTestSupport.TestIdentity admin;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    deleteScannedRows();
    admin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    heldResponses.release();
    deleteScannedRows();
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should admit no further file, match every admitted file with its poster and backdrop, remove"
          + " nothing and return the library HEALTHY when a ServerAdmin stops a scan whose metadata"
          + " provider is held")
  void shouldAdmitNoFurtherFileAndReturnTheLibraryHealthyWhenAServerAdminStopsAHeldScan()
      throws Exception {
    var library = saveMovieLibrary(LibraryStatus.HEALTHY);
    var recordedMovie = saveMovieWithMissingFile(library);
    createMovieFiles();
    stubTmdbScan();
    var admitted = initialAdmissionLimit();
    var end = libraryOperationDriver.nextEnd(library.getId());

    var started =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    heldResponses.awaitHeldResponses(admitted, BOUND);
    var stopped =
        libraryOperationService.stopLibraryScan(authTestSupport.identityOf(admin), library.getId());
    heldResponses.release();
    libraryOperationDriver.awaitEnd(end);

    assertThat(started).isInstanceOf(Outcome.Accepted.class);
    assertThat(stopped.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.STOPPING));
    assertThat(searchRequests()).isEqualTo(admitted);
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .hasSize(admitted + 1)
        .extracting(MediaFile::getStatus)
        .containsOnly(MediaFileStatus.MATCHED);
    assertThat(savedPostersAndBackdrops(library)).isEqualTo(admitted * 2);
    assertThat(movieRepository.findById(recordedMovie.getId())).isPresent();
    assertThat(letterIndexTotal(library)).isEqualTo(admitted + 1);
    assertThat(artworkOfTheCastPerson()).isZero();
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(storedLibraryOperations()).isZero();
  }

  @Test
  @DisplayName(
      "Should refresh no further movie, give every refreshed movie its poster and backdrop and"
          + " return the library UNHEALTHY when a ServerAdmin stops a refresh of an UNHEALTHY"
          + " library whose metadata provider is held")
  void shouldRefreshNoFurtherMovieAndReturnTheLibraryUnhealthyWhenAServerAdminStopsAHeldRefresh()
      throws Exception {
    var library = saveMovieLibrary(LibraryStatus.UNHEALTHY);
    saveMoviesWithTmdbIds(library);
    stubTmdbRefresh();
    var admitted = initialAdmissionLimit();
    var end = libraryOperationDriver.nextEnd(library.getId());

    var started =
        libraryOperationService.startLibraryRefresh(
            authTestSupport.identityOf(admin), library.getId(), ImageRefreshMode.PRESERVE);
    heldResponses.awaitHeldResponses(admitted, BOUND);
    var libraryOperationId = storedLibraryOperationId();
    var stopped =
        libraryOperationService.stopLibraryRefresh(
            authTestSupport.identityOf(admin), library.getId());
    heldResponses.release();
    libraryOperationDriver.awaitEnd(end);

    assertThat(started).isInstanceOf(Outcome.Accepted.class);
    assertThat(stopped.map(Library::getStatus)).isEqualTo(Outcome.accepted(LibraryStatus.STOPPING));
    assertThat(movieMetadataRequests()).isEqualTo(admitted);
    assertThat(metadataResultsFor(libraryOperationId)).isEqualTo(admitted);
    assertThat(savedPostersAndBackdrops(library)).isEqualTo(admitted * 2);
    assertThat(letterIndexTotal(library)).isEqualTo(FILES);
    assertThat(artworkOfTheCastPerson()).isZero();
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(storedLibraryOperations()).isZero();
  }

  @Test
  @DisplayName(
      "Should let every admitted file finish, admit no further file and keep the library SCANNING"
          + " when the scheduler shuts down, then run the scan again and end HEALTHY")
  void shouldLetEveryAdmittedFileFinishAndKeepTheLibraryScanningWhenTheSchedulerShutsDown()
      throws Exception {
    var library = saveMovieLibrary(LibraryStatus.HEALTHY);
    createMovieFiles();
    stubTmdbScan();
    var admitted = initialAdmissionLimit();
    var endAfterRestart = libraryOperationDriver.nextEnd(library.getId());

    contextScheduler.stop();
    try {
      var draining =
          LibraryOperationSchedulerStarter.fromContext(applicationContext).toBuilder()
              .shutdownMaxWait(BOUND)
              .build()
              .start(new AbstractSchedulerListener() {});
      libraryOperationService.startLibraryScan(authTestSupport.identityOf(admin), library.getId());
      heldResponses.awaitHeldResponses(admitted, BOUND);
      try (var shutdown = BoundedTask.start(() -> draining.stop())) {
        // The shutdown waits for the admitted files only after the scheduler marks itself as
        // shutting down.
        await().atMost(BOUND).until(shutdown::isWaiting);
        heldResponses.release();
        shutdown.await(BOUND);
      }

      assertThat(searchRequests()).isEqualTo(admitted);
      assertThat(mediaFileRepository.findByLibraryId(library.getId()))
          .hasSize(admitted)
          .extracting(MediaFile::getStatus)
          .containsOnly(MediaFileStatus.MATCHED);
      assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.SCANNING);
      assertThat(storedLibraryOperations()).isOne();
    } finally {
      contextScheduler.start();
    }

    // The scan that runs again waits for the probes of its files.
    var probeScheduler =
        ProbeSchedulerStarter.fromContext(applicationContext)
            .start(probeExecution, new AbstractSchedulerListener() {}, Clock.systemUTC());
    try {
      libraryOperationDriver.awaitEnd(endAfterRestart);
    } finally {
      probeScheduler.scheduler().stop();
    }

    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .hasSize(FILES)
        .extracting(MediaFile::getStatus)
        .containsOnly(MediaFileStatus.MATCHED);
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(storedLibraryOperations()).isZero();
  }

  // Every admitted task waits for its held answer, so no task reports and the limit stays at its
  // initial value: exactly that many tasks are admitted when the stop arrives.
  private static int initialAdmissionLimit() {
    return AdaptiveAdmission.learnedLimit().getLimit();
  }

  private Library saveMovieLibrary(LibraryStatus status) {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Stopped Movies")
            .backend(LibraryBackend.LOCAL)
            .status(status)
            .filepathUri(FilepathCodec.encode(tempDir))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  // A finished scan would remove this movie, because its file is missing from the library folder.
  private Movie saveMovieWithMissingFile(Library library) {
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Recorded").titleSort("Recorded").library(library).build());
    var missing = tempDir.resolve("Recorded (2001)").resolve("Recorded (2001).mkv");
    mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(missing))
            .filename(missing.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .build());
    return movie;
  }

  private void createMovieFiles() throws IOException {
    for (var title : TITLES) {
      var folder = Files.createDirectories(tempDir.resolve(title + " (2001)"));
      Files.createFile(folder.resolve(title + " (2001).mkv"));
    }
  }

  private void saveMoviesWithTmdbIds(Library library) {
    for (var index = 0; index < FILES; index++) {
      movieRepository.saveAndFlush(
          Movie.builder()
              .title(TITLES.get(index))
              .titleSort(TITLES.get(index))
              .library(library)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalSourceType(ExternalSourceType.TMDB)
                          .externalId(String.valueOf(FIRST_TMDB_ID + index))
                          .build()))
              .build());
    }
  }

  private void stubTmdbScan() {
    for (var index = 0; index < FILES; index++) {
      wireMock.stubFor(held(tmdbSearch(TITLES.get(index), FIRST_TMDB_ID + index)));
      wireMock.stubFor(tmdbMovie(TITLES.get(index), FIRST_TMDB_ID + index));
    }

    stubImages();
  }

  private void stubTmdbRefresh() {
    for (var index = 0; index < FILES; index++) {
      wireMock.stubFor(held(tmdbMovie(TITLES.get(index), FIRST_TMDB_ID + index)));
    }

    stubImages();
  }

  private void stubImages() {
    for (var path : List.of(POSTER_PATH, BACKDROP_PATH, PROFILE_PATH)) {
      wireMock.stubFor(
          get(urlPathEqualTo(path))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "image/jpeg")
                      .withBody(createTestImage(600, 900))));
    }
  }

  private static MappingBuilder tmdbSearch(String title, int tmdbId) {
    return get(urlPathEqualTo("/search/movie"))
        .withQueryParam("query", equalTo(title))
        .willReturn(
            aResponse()
                .withStatus(200)
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
                        .formatted(tmdbId, title, title)));
  }

  private static MappingBuilder tmdbMovie(String title, int tmdbId) {
    return get(urlPathEqualTo("/movie/" + tmdbId))
        .withQueryParam("append_to_response", equalTo("credits,release_dates"))
        .willReturn(
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    """
                    {
                      "id": %d,
                      "title": "%s",
                      "original_title": "%s",
                      "release_date": "2001-01-01",
                      "overview": "A feature that a stop leaves usable.",
                      "runtime": 100,
                      "poster_path": "%s",
                      "backdrop_path": "%s",
                      "genres": [],
                      "production_companies": [],
                      "credits": {
                        "id": %d,
                        "cast": [
                          {"id": %s, "name": "Cast Person", "character": "Lead", "order": 0, "profile_path": "%s"}
                        ],
                        "crew": []
                      },
                      "release_dates": {"results": []}
                    }
                    """
                        .formatted(
                            tmdbId,
                            title,
                            title,
                            POSTER_PATH,
                            BACKDROP_PATH,
                            tmdbId,
                            CAST_PERSON_SOURCE_ID,
                            PROFILE_PATH)));
  }

  private int searchRequests() {
    return wireMock
        .countRequestsMatching(getRequestedFor(urlPathEqualTo("/search/movie")).build())
        .getCount();
  }

  private int movieMetadataRequests() {
    return wireMock
        .countRequestsMatching(getRequestedFor(urlPathMatching("/movie/[0-9]+")).build())
        .getCount();
  }

  private int savedPostersAndBackdrops(Library library) {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM item_result r JOIN movie m ON m.id = r.item_id
        JOIN base_collectable b ON b.id = m.id
        WHERE b.library_id = ? AND r.step = 'ARTWORK' AND r.outcome = 'SUCCEEDED'
          AND r.image_type IN ('POSTER', 'BACKDROP')
        """,
        Integer.class,
        library.getId());
  }

  private int metadataResultsFor(UUID libraryOperationId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM item_result WHERE step = 'METADATA' AND library_operation_id = ?",
        Integer.class,
        libraryOperationId);
  }

  private int letterIndexTotal(Library library) {
    return jdbcTemplate.queryForObject(
        "SELECT coalesce(sum(item_count), 0) FROM library_metadata WHERE library_id = ?",
        Integer.class,
        library.getId());
  }

  // Background artwork leaves a pending request or a recorded result for the person.
  private int artworkOfTheCastPerson() {
    return jdbcTemplate.queryForObject(
        """
        SELECT (SELECT count(*) FROM artwork_request ar JOIN person p ON p.id = ar.target_id
                WHERE p.source_id = ?)
             + (SELECT count(*) FROM item_result r JOIN person p ON p.id = r.item_id
                WHERE p.source_id = ?)
        """,
        Integer.class,
        CAST_PERSON_SOURCE_ID,
        CAST_PERSON_SOURCE_ID);
  }

  private LibraryStatus libraryStatus(Library library) {
    return libraryRepository.findById(library.getId()).orElseThrow().getStatus();
  }

  private UUID storedLibraryOperationId() {
    return jdbcTemplate.queryForObject("SELECT id FROM library_operation", UUID.class);
  }

  private int storedLibraryOperations() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM library_operation", Integer.class);
  }

  private void deleteScannedRows() {
    jdbcTemplate.update("DELETE FROM primary_artwork_scheduled_tasks");
    jdbcTemplate.update("DELETE FROM background_artwork_scheduled_tasks");
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
    // The person's artwork requests and results go with the person.
    jdbcTemplate.update("DELETE FROM person WHERE source_id = ?", CAST_PERSON_SOURCE_ID);
  }
}
