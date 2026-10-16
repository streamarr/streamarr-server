package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.config.ArtworkScheduler;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.support.ArtworkSchedulerStarter;
import com.streamarr.server.support.LibraryOperationDriver;
import com.streamarr.server.support.LibraryOperationSchedulerStarter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * Runs more library scans at once than the probe and primary artwork schedulers have threads. Each
 * scan holds a thread of the library-operation scheduler while it waits for its probes and its
 * artwork, so the probe and artwork threads stay free to produce the results that every scan waits
 * for.
 */
@Tag("IntegrationTest")
@DisplayName("Library scans beyond the probe threads")
class LibraryScansBeyondProbeThreadsIT extends AbstractScanningIntegrationTest {

  // ProbeSchedulerStarter and ArtworkSchedulerStarter each start a scheduler with two threads.
  private static final int PROBE_AND_ARTWORK_THREADS = 2;
  private static final int LIBRARIES = PROBE_AND_ARTWORK_THREADS + 2;
  private static final int FIRST_TMDB_ID = 8_100_000;
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final List<String> TITLES =
      List.of("Amber Harbor", "Brisk Meadow", "Calm Canyon", "Dusky Lantern");

  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private ArtworkPrioritySchedulers artworkPrioritySchedulers;
  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private LibraryOperationDriver libraryOperationDriver;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private PersistedProbeReader probeReader;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext applicationContext;

  @TempDir Path tempDir;

  private final List<Library> libraries = new ArrayList<>();
  private LibraryOperationScheduler libraryOperationScheduler;
  private ArtworkScheduler primaryArtworkScheduler;
  private ProbeSchedulerStarter.StartedProbeScheduler probeScheduler;

  // The test runs its own schedulers with known thread counts, so the context's schedulers must not
  // pick the test's executions.
  @BeforeEach
  void replaceContextSchedulers() {
    contextScheduler.stop();
    artworkPrioritySchedulers.stop();
    wireMock.resetAll();
    deleteScheduledTasks();
  }

  @AfterEach
  void restoreContextSchedulers() {
    if (libraryOperationScheduler != null) {
      libraryOperationScheduler.stop();
    }

    if (primaryArtworkScheduler != null) {
      primaryArtworkScheduler.stop();
    }

    if (probeScheduler != null) {
      probeScheduler.scheduler().stop();
    }

    deleteScheduledTasks();
    libraries.forEach(
        library ->
            mediaFileRepository.deleteAll(mediaFileRepository.findByLibraryId(library.getId())));
    movieRepository.deleteAll();
    libraryRepository.deleteAll(libraries);
    artworkPrioritySchedulers.start();
    contextScheduler.start();
  }

  @Test
  @DisplayName(
      "Should end every scan HEALTHY with its file matched, probed and its artwork saved when more"
          + " libraries scan at once than the probe and artwork schedulers have threads")
  void shouldEndEveryScanHealthyWhenMoreLibrariesScanThanTheProbeAndArtworkSchedulersHaveThreads()
      throws Exception {
    for (var index = 0; index < LIBRARIES; index++) {
      libraries.add(movieLibraryWithOneFile(index));
      stubTmdbMovie(index);
    }

    stubImage("/beyond-threads-poster.jpg");
    stubImage("/beyond-threads-backdrop.jpg");
    var everyScanRunning = new CountDownLatch(LIBRARIES);
    startSchedulers(everyScanRunning);

    var ends = new ArrayList<CompletableFuture<Void>>();
    for (var library : libraries) {
      ends.add(libraryOperationDriver.nextEnd(library.getId()));
      assertThat(libraryOperationService.startLibraryScanAsSystemActor(library.getId()))
          .isInstanceOf(Outcome.Accepted.class);
    }

    ends.forEach(libraryOperationDriver::awaitEnd);

    assertThat(everyScanRunning.getCount()).as("scans that never ran at once").isZero();
    for (var library : libraries) {
      assertThat(libraryRepository.findById(library.getId()).orElseThrow().getStatus())
          .isEqualTo(LibraryStatus.HEALTHY);
      assertThat(mediaFileRepository.findByLibraryId(library.getId()))
          .singleElement()
          .satisfies(
              file -> {
                assertThat(file.getStatus()).isEqualTo(MediaFileStatus.MATCHED);
                assertThat(probeReader.find(file.getId())).isPresent();
              });
      assertThat(savedPrimaryImagesOf(library)).isEqualTo(2);
    }
  }

  // Each probe waits until every scan runs, so the probe threads are busy while all the scans wait
  // for probes at once.
  private void startSchedulers(CountDownLatch everyScanRunning) {
    var producer = new FakeFfprobeService();
    FfprobeService probingOnceEveryScanRuns =
        request -> {
          if (!awaitQuietly(everyScanRunning)) {
            throw new ProbeExecutionException(
                ItemFailureReason.TEMPORARY, "Not every scan ran at once");
          }

          return producer.probe(request);
        };
    probeScheduler =
        ProbeSchedulerStarter.fromContext(applicationContext)
            .start(
                probeExecution.toBuilder().producer(probingOnceEveryScanRuns).build(),
                new AbstractSchedulerListener() {},
                Clock.systemUTC());
    primaryArtworkScheduler =
        ArtworkSchedulerStarter.fromContext(applicationContext)
            .start(ArtworkPriority.PRIMARY, new AbstractSchedulerListener() {});
    libraryOperationScheduler =
        LibraryOperationSchedulerStarter.fromContext(applicationContext).toBuilder()
            .threads(LIBRARIES)
            .build()
            .start(
                new AbstractSchedulerListener() {
                  @Override
                  public void onExecutionStart(CurrentlyExecuting execution) {
                    everyScanRunning.countDown();
                  }
                });
  }

  private static boolean awaitQuietly(CountDownLatch latch) {
    try {
      return latch.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private Library movieLibraryWithOneFile(int index) throws IOException {
    var title = TITLES.get(index);
    var root = Files.createDirectories(tempDir.resolve("library-" + index));
    var folder = Files.createDirectories(root.resolve(title + " (2001)"));
    Files.createFile(folder.resolve(title + " (2001).mkv"));
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Beyond threads " + index)
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(root))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  private void stubTmdbMovie(int index) {
    var title = TITLES.get(index);
    var tmdbId = FIRST_TMDB_ID + index;
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
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
                            .formatted(tmdbId, title, title))));
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/" + tmdbId))
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
                          "overview": "A feature in one of several libraries.",
                          "runtime": 100,
                          "poster_path": "/beyond-threads-poster.jpg",
                          "backdrop_path": "/beyond-threads-backdrop.jpg",
                          "genres": [],
                          "production_companies": [],
                          "credits": {"id": %d, "cast": [], "crew": []},
                          "release_dates": {"results": []}
                        }
                        """
                            .formatted(tmdbId, title, title, tmdbId))));
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

  private int savedPrimaryImagesOf(Library library) {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*) FROM item_result r JOIN base_collectable b ON b.id = r.item_id
        WHERE r.step = 'ARTWORK' AND r.outcome = 'SUCCEEDED'
          AND r.image_type IN ('POSTER', 'BACKDROP') AND b.library_id = ?
        """,
        Integer.class,
        library.getId());
  }

  private void deleteScheduledTasks() {
    jdbcTemplate.update("DELETE FROM scheduled_tasks");
    jdbcTemplate.update("DELETE FROM " + ArtworkSchedulerStarter.PRIMARY_TABLE);
    jdbcTemplate.update("DELETE FROM library_operation");
    jdbcTemplate.update("DELETE FROM " + LibraryOperationScheduler.TABLE_NAME);
  }
}
