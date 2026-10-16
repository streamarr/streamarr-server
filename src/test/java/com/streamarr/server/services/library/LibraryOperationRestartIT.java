package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.streamarr.server.support.HeldResponses.held;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.config.ArtworkRunProperties;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.ArtworkPriority;
import com.streamarr.server.services.ArtworkRequestService;
import com.streamarr.server.services.ArtworkRunWaiter;
import com.streamarr.server.services.concurrency.Sleeper;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.ArtworkSchedulerStarter;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.LibraryOperationSchedulerStarter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("IntegrationTest")
@DisplayName("Library operation restart integration tests")
class LibraryOperationRestartIT extends AbstractScanningIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(30);

  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private ArtworkPrioritySchedulers artworkPrioritySchedulers;

  @TempDir Path tempDir;

  private final List<LibraryOperationScheduler> startedSchedulers = new ArrayList<>();
  private ProbeSchedulerStarter.StartedProbeScheduler probeScheduler;
  private AuthTestSupport.TestIdentity admin;

  // The test runs its own library-operation schedulers against the shared table, so the context's
  // scheduler must not pick the test's executions.
  @BeforeEach
  void setUp() {
    contextScheduler.stop();
    wireMock.resetAll();
    deleteLibraryOperations();
    jdbcTemplate.update("DELETE FROM " + ArtworkSchedulerStarter.PRIMARY_TABLE);
    mediaFileRepository.deleteAll();
    movieRepository.deleteAll();
    libraryRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM scheduled_tasks");
    probeScheduler =
        ProbeSchedulerStarter.fromContext(applicationContext)
            .start(probeExecution, new AbstractSchedulerListener() {}, Clock.systemUTC());
    admin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    startedSchedulers.forEach(LibraryOperationScheduler::stop);
    probeScheduler.scheduler().stop();
    deleteLibraryOperations();
    authTestSupport.deleteIdentity(admin);
    contextScheduler.start();
  }

  @Test
  @DisplayName(
      "Should keep the library SCANNING after a restart until the artwork requested before the"
          + " restart has results for the scan, then end HEALTHY")
  void shouldKeepTheLibraryScanningAfterARestartUntilTheArtworkRequestedBeforeTheRestartHasResults()
      throws Exception {
    var library = createMovieLibrary();
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch();
    stubTmdbMovieMetadata();
    artworkPrioritySchedulers.stop();
    try {
      scanAcrossARestartWithTheArtworkLeftWithoutResults(library);
    } finally {
      artworkPrioritySchedulers.start();
    }
  }

  private void scanAcrossARestartWithTheArtworkLeftWithoutResults(Library library)
      throws Exception {
    var beforeRestart = startScheduler(new AbstractSchedulerListener() {});

    var outcome =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    // The file's task holds its admission until its artwork has results, so the restart comes
    // while the task waits for the poster and backdrop that it requested.
    await().atMost(BOUND).until(() -> artworkRequestsOfTheLibraryOperation() == 1);
    beforeRestart.stop();
    var libraryOperationId = storedLibraryOperationId();

    var waitingOnArtwork = new CompletableFuture<Void>();
    var completed = new CompletableFuture<ExecutionComplete>();
    startScheduler(
        executionWaitingOnArtworkInto(waitingOnArtwork),
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete complete) {
            completed.complete(complete);
          }
        });
    CompletableFuture.anyOf(waitingOnArtwork, completed).get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(completed).as("the scan ended before its artwork had results").isNotDone();
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.SCANNING);
    assertThat(artworkResultsFor(libraryOperationId)).isZero();

    var artworkScheduler =
        ArtworkSchedulerStarter.fromContext(applicationContext)
            .start(ArtworkPriority.PRIMARY, new AbstractSchedulerListener() {});
    try {
      completed.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    } finally {
      artworkScheduler.stop();
    }

    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(artworkResultsFor(libraryOperationId)).isEqualTo(2);
    assertThat(storedLibraryOperations()).isZero();
  }

  // The execution's wait for the operation's artwork results completes the future when it first
  // finds a result missing.
  private LibraryOperationExecution executionWaitingOnArtworkInto(
      CompletableFuture<Void> waitingOnArtwork) {
    var contextSleeper = applicationContext.getBean(Sleeper.class);
    var artworkRunWaiter =
        ArtworkRunWaiter.builder()
            .requests(applicationContext.getBean(ArtworkRequestRepository.class))
            .itemResults(applicationContext.getBean(ItemResultRepository.class))
            .sleeper(
                duration -> {
                  waitingOnArtwork.complete(null);
                  contextSleeper.sleep(duration);
                })
            .clock(Clock.systemUTC())
            .properties(applicationContext.getBean(ArtworkRunProperties.class))
            .build();
    var fileDiscoveryRuns =
        FileDiscoveryRuns.builder()
            .artworkRequestService(applicationContext.getBean(ArtworkRequestService.class))
            .artworkRunWaiter(artworkRunWaiter)
            .probeRuns(applicationContext.getBean(ProbeRuns.class))
            .mediaFiles(mediaFileRepository)
            .build();
    return LibraryOperationExecution.builder()
        .libraryOperations(applicationContext.getBean(LibraryOperationRepository.class))
        .libraryRepository(libraryRepository)
        .libraryFileWalk(applicationContext.getBean(LibraryFileWalk.class))
        .fileDiscoveryRuns(fileDiscoveryRuns)
        .libraryFolderCheck(applicationContext.getBean(LibraryFolderCheck.class))
        .libraryRefreshService(applicationContext.getBean(LibraryRefreshService.class))
        .finishingStepsRunner(
            applicationContext.getBean(LibraryOperationFinishingStepsRunner.class))
        .letterIndexRecalculator(applicationContext.getBean(LetterIndexRecalculator.class))
        .eventPublisher(applicationContext)
        .transactionManager(applicationContext.getBean(PlatformTransactionManager.class))
        .clock(Clock.systemUTC())
        .metrics(applicationContext.getBean(LibraryOperationMetrics.class))
        .build();
  }

  @Test
  @DisplayName(
      "Should run the scan again and end HEALTHY with every file matched when the"
          + " library-operation scheduler stops mid-scan and a new one starts on the same database")
  void shouldRunTheScanAgainAndEndHealthyWhenTheSchedulerStopsMidScanAndANewOneStarts()
      throws Exception {
    var library = createMovieLibrary();
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubHeldTmdbMovieSearch();
    stubTmdbMovieMetadata();
    var beforeRestart = startScheduler(new AbstractSchedulerListener() {});

    var outcome =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    heldResponses.awaitHeldResponses(1, BOUND);
    beforeRestart.stop();

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.SCANNING);

    heldResponses.release();
    var completed = new CompletableFuture<ExecutionComplete>();
    startScheduler(
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete complete) {
            completed.complete(complete);
          }
        });
    completed.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .extracting(MediaFile::getStatus)
        .containsExactly(MediaFileStatus.MATCHED);
    assertThat(storedLibraryOperations()).isZero();
    assertThat(scheduledLibraryOperations()).isZero();
  }

  @Test
  @DisplayName(
      "Should run the scan again and end HEALTHY when the instance that picked it died without"
          + " stopping its scheduler")
  void shouldRunTheScanAgainAndEndHealthyWhenTheInstanceThatPickedItDied() throws Exception {
    var library = createMovieLibrary();
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch();
    stubTmdbMovieMetadata();
    var outcome =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    leaveExecutionPickedByADeadInstance();

    var completed = new CompletableFuture<ExecutionComplete>();
    startScheduler(
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete complete) {
            completed.complete(complete);
          }
        });
    completed.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.HEALTHY);
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .extracting(MediaFile::getStatus)
        .containsExactly(MediaFileStatus.MATCHED);
    assertThat(storedLibraryOperations()).isZero();
    assertThat(scheduledLibraryOperations()).isZero();
  }

  @Test
  @DisplayName(
      "Should remove nothing and end the scan FAILED with the library UNHEALTHY when the scan runs"
          + " again after a restart and the library folder was emptied in between")
  void shouldRemoveNothingAndEndTheScanFailedWhenTheLibraryFolderEmptiesBeforeTheScanRunsAgain()
      throws Exception {
    var library = createMovieLibrary();
    var movie =
        movieRepository.saveAndFlush(Movie.builder().title("Recorded").library(library).build());
    var recordedFile =
        saveMatchedMediaFile(library, movie, createMovieFile("Recorded", "Recorded.mkv"));
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubHeldTmdbMovieSearch();
    stubTmdbMovieMetadata();
    var beforeRestart = startScheduler(new AbstractSchedulerListener() {});
    var outcome =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    heldResponses.awaitHeldResponses(1, BOUND);
    beforeRestart.stop();
    var mediaFilesBeforeRestart = mediaFileRepository.findByLibraryId(library.getId()).size();
    var failedScansBefore = failedScans();
    var emptyFolderFailuresBefore = emptyFolderFailures();
    FileUtils.cleanDirectory(tempDir.toFile());

    var completed = new CompletableFuture<ExecutionComplete>();
    startScheduler(
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete complete) {
            completed.complete(complete);
          }
        });
    completed.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(mediaFileRepository.findById(recordedFile.getId())).isPresent();
    assertThat(movieRepository.findById(movie.getId())).isPresent();
    assertThat(mediaFileRepository.findByLibraryId(library.getId()))
        .hasSize(mediaFilesBeforeRestart);
    assertThat(failedScans()).isEqualTo(failedScansBefore + 1);
    assertThat(emptyFolderFailures()).isEqualTo(emptyFolderFailuresBefore + 1);
    assertThat(storedLibraryOperations()).isZero();
    assertThat(scheduledLibraryOperations()).isZero();
  }

  private MediaFile saveMatchedMediaFile(Library library, Movie movie, Path path) {
    return mediaFileRepository.saveAndFlush(
        MediaFile.builder()
            .filepathUri(FilepathCodec.encode(path))
            .filename(path.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .libraryId(library.getId())
            .mediaId(movie.getId())
            .build());
  }

  private long failedScans() {
    var timer =
        meterRegistry
            .find(LibraryOperationMetrics.DURATION)
            .tag("kind", "scan")
            .tag("conclusion", "failed")
            .timer();
    if (timer == null) {
      return 0;
    }

    return timer.count();
  }

  private double emptyFolderFailures() {
    var counter =
        meterRegistry.find(LibraryFolderCheck.FAILURES_METRIC).tag("reason", "empty").counter();
    if (counter == null) {
      return 0;
    }

    return counter.count();
  }

  private void leaveExecutionPickedByADeadInstance() {
    var picked =
        jdbcTemplate.update(
            "UPDATE "
                + LibraryOperationScheduler.TABLE_NAME
                + " SET picked = true, picked_by = 'dead-instance', version = version + 1,"
                + " last_heartbeat = now() - interval '1 day'");
    assertThat(picked).isOne();
  }

  private LibraryOperationScheduler startScheduler(AbstractSchedulerListener listener) {
    var scheduler =
        LibraryOperationSchedulerStarter.fromContext(applicationContext).start(listener);
    startedSchedulers.add(scheduler);
    return scheduler;
  }

  private LibraryOperationScheduler startScheduler(
      LibraryOperationExecution execution, AbstractSchedulerListener listener) {
    var scheduler =
        LibraryOperationSchedulerStarter.fromContext(applicationContext).toBuilder()
            .execution(execution)
            .build()
            .start(listener);
    startedSchedulers.add(scheduler);
    return scheduler;
  }

  // Requests that earlier test classes left for other targets stay out of the count.
  private int artworkRequestsOfTheLibraryOperation() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM artwork_request
        JOIN library_operation ON library_operation.id = artwork_request.library_operation_id
        """,
        Integer.class);
  }

  private UUID storedLibraryOperationId() {
    return jdbcTemplate.queryForObject("SELECT id FROM library_operation", UUID.class);
  }

  private int artworkResultsFor(UUID libraryOperationId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM item_result WHERE step = 'ARTWORK' AND library_operation_id = ?",
        Integer.class,
        libraryOperationId);
  }

  private LibraryStatus libraryStatus(Library library) {
    return libraryRepository.findById(library.getId()).orElseThrow().getStatus();
  }

  private int storedLibraryOperations() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM library_operation", Integer.class);
  }

  private int scheduledLibraryOperations() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + LibraryOperationScheduler.TABLE_NAME, Integer.class);
  }

  private void deleteLibraryOperations() {
    jdbcTemplate.update("DELETE FROM library_operation");
    jdbcTemplate.update("DELETE FROM " + LibraryOperationScheduler.TABLE_NAME);
  }

  private Library createMovieLibrary() {
    return libraryRepository.saveAndFlush(
        Library.builder()
            .name("Movies")
            .backend(LibraryBackend.LOCAL)
            .status(LibraryStatus.HEALTHY)
            .filepathUri(FilepathCodec.encode(tempDir))
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .type(MediaType.MOVIE)
            .build());
  }

  private Path createMovieFile(String directory, String filename) throws IOException {
    var dir = Files.createDirectories(tempDir.resolve(directory));
    return Files.createFile(dir.resolve(filename));
  }

  private void stubTmdbMovieSearch() {
    wireMock.stubFor(tmdbMovieSearch());
  }

  private void stubHeldTmdbMovieSearch() {
    wireMock.stubFor(held(tmdbMovieSearch()));
  }

  private static MappingBuilder tmdbMovieSearch() {
    return get(urlPathEqualTo("/search/movie"))
        .withQueryParam("query", equalTo("Inception"))
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
                          "id": 27205,
                          "title": "Inception",
                          "original_title": "Inception",
                          "release_date": "2010-07-16",
                          "popularity": 100.0,
                          "vote_count": 1000,
                          "vote_average": 8.5
                        }
                      ],
                      "total_results": 1,
                      "total_pages": 1
                    }
                    """));
  }

  private void stubTmdbMovieMetadata() {
    wireMock.stubFor(
        get(urlPathEqualTo("/movie/27205"))
            .withQueryParam("append_to_response", equalTo("credits,release_dates"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "id": 27205,
                          "title": "Inception",
                          "original_title": "Inception",
                          "release_date": "2010-07-16",
                          "overview": "A thief who steals corporate secrets.",
                          "runtime": 148,
                          "imdb_id": "tt1375666",
                          "genres": [{"id": 28, "name": "Action"}],
                          "production_companies": [],
                          "credits": {"id": 27205, "cast": [], "crew": []},
                          "release_dates": {"results": []}
                        }
                        """)));
  }
}
