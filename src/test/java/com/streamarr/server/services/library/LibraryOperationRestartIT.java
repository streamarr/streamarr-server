package com.streamarr.server.services.library;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.config.LibraryOperationScheduler;
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
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.LibraryOperationSchedulerStarter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

@Tag("IntegrationTest")
@DisplayName("Library operation restart integration tests")
class LibraryOperationRestartIT extends AbstractScanningIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final Duration SEARCH_HELD_FOR = Duration.ofSeconds(60);

  @Autowired private LibraryOperationScheduler contextScheduler;
  @Autowired private LibraryOperationService libraryOperationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private ProbeExecution probeExecution;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext applicationContext;

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
      "Should run the scan again and end HEALTHY with every file matched when the"
          + " library-operation scheduler stops mid-scan and a new one starts on the same database")
  void shouldRunTheScanAgainAndEndHealthyWhenTheSchedulerStopsMidScanAndANewOneStarts()
      throws Exception {
    var library = createMovieLibrary();
    createMovieFile("Inception (2010)", "Inception (2010).mkv");
    stubTmdbMovieSearch(SEARCH_HELD_FOR);
    stubTmdbMovieMetadata();
    var started = new CompletableFuture<Void>();
    var beforeRestart =
        startScheduler(
            new AbstractSchedulerListener() {
              @Override
              public void onExecutionStart(CurrentlyExecuting execution) {
                started.complete(null);
              }
            });

    var outcome =
        libraryOperationService.startLibraryScan(
            authTestSupport.identityOf(admin), library.getId());
    started.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    beforeRestart.stop();

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(libraryStatus(library)).isEqualTo(LibraryStatus.SCANNING);

    stubTmdbMovieSearch(Duration.ZERO);
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
    stubTmdbMovieSearch(Duration.ZERO);
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

  private void createMovieFile(String directory, String filename) throws IOException {
    var dir = Files.createDirectories(tempDir.resolve(directory));
    Files.createFile(dir.resolve(filename));
  }

  private void stubTmdbMovieSearch(Duration delay) {
    wireMock.stubFor(
        get(urlPathEqualTo("/search/movie"))
            .withQueryParam("query", equalTo("Inception"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay((int) delay.toMillis())
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
                        """)));
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
