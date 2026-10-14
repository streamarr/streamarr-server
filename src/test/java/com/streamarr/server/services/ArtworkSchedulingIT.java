package com.streamarr.server.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.config.ArtworkScheduler;
import com.streamarr.server.config.ArtworkSchedulers;
import com.streamarr.server.config.ArtworkSchedulingConfiguration;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ArtworkState;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.BoundedTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Scheduling Integration Tests")
class ArtworkSchedulingIT extends AbstractWireMockIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final ImageSource POSTER = new TmdbImageSource(ImageType.POSTER, "/poster.jpg");
  private static final ImageSource BACKDROP =
      new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg");

  @Autowired private ArtworkSchedulers contextSchedulers;
  @Autowired private ArtworkService artworkService;
  @Autowired private ArtworkRuns artworkRuns;
  @Autowired private ArtworkSteps artworkSteps;
  @Autowired private ArtworkRequestRepository requests;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private ImageRepository imageRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private DbSchedulerCustomizer customizer;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Environment environment;

  private final List<ArtworkScheduler> startedSchedulers = new ArrayList<>();
  private UUID movieId;

  // Each test runs its own artwork schedulers against the shared tables, so the context's
  // schedulers must not pick the test's executions.
  @BeforeEach
  void setUp() {
    contextSchedulers.stop();
    jdbcTemplate.update("DELETE FROM primary_artwork_scheduled_tasks");
    jdbcTemplate.update("DELETE FROM background_artwork_scheduled_tasks");
    wireMock.resetAll();
    movieId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
  }

  @AfterEach
  void tearDown() {
    startedSchedulers.forEach(ArtworkScheduler::stop);
    contextSchedulers.start();
  }

  @Test
  @DisplayName(
      "Should save the poster and backdrop with their results when the server restarts before the"
          + " downloads finish")
  void shouldSaveThePosterAndBackdropWithTheirResultsWhenTheServerRestartsBeforeDownloadsFinish()
      throws Exception {
    stubImage("/poster.jpg", Duration.ofSeconds(10));
    stubImage("/backdrop.jpg", Duration.ofSeconds(10));
    var started = new CompletableFuture<Void>();
    var beforeRestart =
        startPrimaryScheduler(
            new AbstractSchedulerListener() {
              @Override
              public void onExecutionStart(CurrentlyExecuting execution) {
                started.complete(null);
              }
            });
    var run = requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    started.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    beforeRestart.stop();

    assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
        .isEmpty();
    stubImage("/poster.jpg", Duration.ZERO);
    stubImage("/backdrop.jpg", Duration.ZERO);
    startPrimaryScheduler(new AbstractSchedulerListener() {});

    var summary = awaitRun(run);

    assertThat(summary.counts()).isEqualTo(ArtworkCounts.builder().saved(2).build());
    assertThat(artworkOutcomes())
        .containsOnly(new ItemOutcome.Succeeded(), new ItemOutcome.Succeeded())
        .hasSize(2);
    assertThat(imageRepository.findByEntityIdAndEntityType(movieId, ImageEntityType.MOVIE))
        .extracting(image -> image.getImageType())
        .containsOnly(ImageType.POSTER, ImageType.BACKDROP);
  }

  @Test
  @DisplayName("Should run the steps again when a crashed server left their execution picked")
  void shouldRunTheStepsAgainWhenACrashedServerLeftTheirExecutionPicked() throws Exception {
    stubImage("/poster.jpg", Duration.ZERO);
    stubImage("/backdrop.jpg", Duration.ZERO);
    var run = requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    jdbcTemplate.update(
        "UPDATE primary_artwork_scheduled_tasks SET picked = true, picked_by = 'crashed-server',"
            + " last_heartbeat = now() - interval '1 hour'");

    startPrimaryScheduler(new AbstractSchedulerListener() {});

    assertThat(awaitRun(run).counts()).isEqualTo(ArtworkCounts.builder().saved(2).build());
    assertThat(scheduledArtworkTasks("primary_artwork_scheduled_tasks")).isZero();
  }

  @Test
  @DisplayName(
      "Should record a temporary failure and save the image later when the host answers too many"
          + " requests past the client's retries")
  void shouldRecordATemporaryFailureAndSaveTheImageLaterWhenTheHostAnswersTooManyRequests()
      throws Exception {
    stubTooManyRequestsThenImage("/poster.jpg", 6);
    stubImage("/backdrop.jpg", Duration.ZERO);
    var failed = new CompletableFuture<ExecutionComplete>();
    var saved = new CompletableFuture<ExecutionComplete>();
    startPrimaryScheduler(
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionComplete(ExecutionComplete completed) {
            if (!failed.isDone()) {
              failed.complete(completed);
              return;
            }

            saved.complete(completed);
          }
        });
    var run = requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER, BACKDROP);

    var firstAttempt = awaitRun(run);
    failed.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(firstAttempt.counts()).isEqualTo(ArtworkCounts.builder().saved(1).failed(1).build());
    assertThat(posterResult().outcome())
        .isInstanceOfSatisfying(
            ItemOutcome.Failed.class,
            outcome -> assertThat(outcome.reason()).isEqualTo(ItemFailureReason.TEMPORARY));
    assertThat(consecutiveFailures()).isOne();

    saved.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
    assertThat(
            imageRepository.findByEntityIdAndEntityTypeAndImageType(
                movieId, ImageEntityType.MOVIE, ImageType.POSTER))
        .isNotEmpty();
  }

  @Test
  @DisplayName(
      "Should save nothing from a preserving attempt that finishes after a forced refresh, and"
          + " wait for the refresh's own results")
  void shouldSaveNothingFromAPreservingAttemptThatFinishesAfterAForcedRefresh() throws Exception {
    imageRepository.save(
        imageBuilder(movieId).key("/poster.jpg").path("movie/poster-small.jpg").build());
    stubImage("/poster.jpg", Duration.ZERO);
    stubImage("/backdrop.jpg", Duration.ofSeconds(3));
    var started = new CompletableFuture<Void>();
    startPrimaryScheduler(
        new AbstractSchedulerListener() {
          @Override
          public void onExecutionStart(CurrentlyExecuting execution) {
            started.complete(null);
          }
        });
    requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER, BACKDROP);
    var preserveGeneration = requests.findRequest(movie()).orElseThrow().generation();
    started.get(BOUND.toSeconds(), TimeUnit.SECONDS);

    var forced = requestMovieArtwork(ImageRefreshMode.FORCE_REFRESH, POSTER, BACKDROP);

    assertThat(awaitRun(forced).counts()).isEqualTo(ArtworkCounts.builder().saved(2).build());
    assertThat(itemResults.findByItem(movieId, ImageEntityType.MOVIE))
        .filteredOn(result -> result.step() == ItemStep.ARTWORK)
        .hasSize(2)
        .allSatisfy(
            result -> assertThat(result.artworkGeneration()).isGreaterThan(preserveGeneration));
  }

  @Test
  @DisplayName("Should remove the scheduled task when its item was deleted before the steps ran")
  void shouldRemoveTheScheduledTaskWhenItsItemWasDeletedBeforeTheStepsRan() throws Exception {
    requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER);
    movieRepository.deleteById(movieId);
    var completed = new CompletableFuture<ExecutionComplete>();

    startPrimaryScheduler(completedInto(completed));

    completed.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    assertThat(scheduledArtworkTasks("primary_artwork_scheduled_tasks")).isZero();
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the request and retry after a backoff when an attempt fails unexpectedly")
  void shouldKeepTheRequestAndRetryAfterABackoffWhenAnAttemptFailsUnexpectedly() throws Exception {
    requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER);
    var unreadable = new DataAccessResourceFailureException("database unavailable");
    var failingSteps =
        artworkSteps.toBuilder().requests(new UnreadableRequests(requests, unreadable)).build();
    var completed = new CompletableFuture<ExecutionComplete>();
    var beforeAttempt = Instant.now();

    startPrimaryScheduler(failingSteps, completedInto(completed));

    assertThat(completed.get(BOUND.toSeconds(), TimeUnit.SECONDS).getCause()).contains(unreadable);
    assertThat(requests.findRequest(movie())).isPresent();
    assertThat(consecutiveFailures()).isOne();
    assertThat(nextExecutionTime()).isAfter(beforeAttempt.plusSeconds(4));
  }

  @Test
  @DisplayName("Should keep artwork executions out of the table whose threads run probes")
  void shouldKeepArtworkExecutionsOutOfTheTableWhoseThreadsRunProbes() {
    var probeExecutionsBefore = scheduledArtworkTasks("scheduled_tasks");
    var personId =
        personRepository
            .saveAndFlush(
                Person.builder()
                    .name("Artwork Person")
                    .sourceId(UUID.randomUUID().toString())
                    .build())
            .getId();

    requestMovieArtwork(ImageRefreshMode.PRESERVE, POSTER);
    transactionTemplate.executeWithoutResult(
        _ ->
            artworkService.requestBackgroundArtwork(
                ArtworkSources.builder()
                    .entityId(personId)
                    .entityType(ImageEntityType.PERSON)
                    .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/profile.jpg")))
                    .build(),
                ImageRefreshMode.PRESERVE));

    assertThat(scheduledArtworkTasks("primary_artwork_scheduled_tasks")).isOne();
    assertThat(scheduledArtworkTasks("background_artwork_scheduled_tasks")).isOne();
    assertThat(scheduledArtworkTasks("scheduled_tasks")).isEqualTo(probeExecutionsBefore);
  }

  @Test
  @DisplayName(
      "Should store no request and schedule no steps when the metadata transaction rolls back")
  void shouldStoreNoRequestAndScheduleNoStepsWhenTheMetadataTransactionRollsBack() {
    var run = artworkService.openRun("scan", ImageRefreshMode.PRESERVE);

    transactionTemplate.executeWithoutResult(
        status -> {
          artworkService.requestPrimaryArtwork(run, movieArtwork(POSTER));
          status.setRollbackOnly();
        });

    assertThat(requests.findRequest(movie())).isEmpty();
    assertThat(scheduledArtworkTasks("primary_artwork_scheduled_tasks")).isZero();
  }

  private ArtworkScheduler startPrimaryScheduler(AbstractSchedulerListener listener) {
    return startPrimaryScheduler(artworkSteps, listener);
  }

  private ArtworkScheduler startPrimaryScheduler(
      ArtworkSteps steps, AbstractSchedulerListener listener) {
    var properties = ArtworkSchedulingConfiguration.schedulerProperties(environment);
    properties.setShutdownMaxWait(Duration.ofMillis(200));
    properties.setPollingInterval(Duration.ofMillis(100));
    var scheduler =
        ArtworkScheduler.builder()
            .schedulerProperties(properties)
            .customizer(customizer)
            .dataSource(dataSource)
            .transactionManager(transactionManager)
            .steps(steps)
            .requests(requests)
            .tableName(ArtworkSchedulingConfiguration.PRIMARY_TABLE)
            .taskName(ArtworkSchedulingConfiguration.PRIMARY_TASK)
            .threads(2)
            .clock(Clock.systemUTC())
            .listeners(List.of(listener))
            .build();
    scheduler.start();
    startedSchedulers.add(scheduler);
    return scheduler;
  }

  private ArtworkRun requestMovieArtwork(ImageRefreshMode refreshMode, ImageSource... sources) {
    var run = artworkService.openRun("scan", refreshMode);
    transactionTemplate.executeWithoutResult(
        _ -> artworkService.requestPrimaryArtwork(run, movieArtwork(sources)));
    run.close();
    return run;
  }

  private ArtworkRunSummary awaitRun(ArtworkRun run) throws Exception {
    try (var wait = BoundedTask.start(() -> artworkRuns.awaitResults(run))) {
      return wait.await(BOUND);
    }
  }

  private ArtworkSources movieArtwork(ImageSource... sources) {
    return ArtworkSources.builder()
        .entityId(movieId)
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(sources))
        .build();
  }

  private ArtworkItem movie() {
    return new ArtworkItem(movieId, ImageEntityType.MOVIE);
  }

  private List<ItemOutcome> artworkOutcomes() {
    return itemResults.findByItem(movieId, ImageEntityType.MOVIE).stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .map(ItemResult::outcome)
        .toList();
  }

  private ItemResult posterResult() {
    return itemResults.findByItem(movieId, ImageEntityType.MOVIE).stream()
        .filter(result -> result.imageType() == ImageType.POSTER)
        .findFirst()
        .orElseThrow();
  }

  private int consecutiveFailures() {
    return jdbcTemplate.queryForObject(
        "SELECT consecutive_failures FROM primary_artwork_scheduled_tasks", Integer.class);
  }

  private Instant nextExecutionTime() {
    return jdbcTemplate
        .queryForObject(
            "SELECT execution_time FROM primary_artwork_scheduled_tasks", OffsetDateTime.class)
        .toInstant();
  }

  private static AbstractSchedulerListener completedInto(
      CompletableFuture<ExecutionComplete> completed) {
    return new AbstractSchedulerListener() {
      @Override
      public void onExecutionComplete(ExecutionComplete complete) {
        completed.complete(complete);
      }
    };
  }

  /** Fails every read of a pending request, as a database outage would. */
  private record UnreadableRequests(ArtworkRequestRepository delegate, RuntimeException failure)
      implements ArtworkRequestRepository {

    @Override
    public SavedArtworkRequest saveRequest(
        ArtworkSources artwork, ImageRefreshMode refreshMode, Instant requestedAt) {
      return delegate.saveRequest(artwork, refreshMode, requestedAt);
    }

    @Override
    public Optional<PendingArtworkRequest> findRequest(ArtworkItem item) {
      throw failure;
    }

    @Override
    public Optional<Long> lockPendingGeneration(ArtworkItem item) {
      return delegate.lockPendingGeneration(item);
    }

    @Override
    public boolean tryCompleteGeneration(ArtworkItem item, long generation) {
      return delegate.tryCompleteGeneration(item, generation);
    }

    @Override
    public List<ArtworkState> findArtworkStates(Collection<ArtworkItem> items) {
      return delegate.findArtworkStates(items);
    }

    @Override
    public Map<ImageEntityType, Long> countPendingImagesByItemType() {
      return delegate.countPendingImagesByItemType();
    }
  }

  private int scheduledArtworkTasks(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private void stubImage(String path, Duration delay) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withFixedDelay((int) delay.toMillis())
                    .withBody(createTestImage(600, 900))));
  }

  // The HTTP client retries a 429 five times, so the first attempt ends with the sixth answer.
  private void stubTooManyRequestsThenImage(String path, int tooManyRequests) {
    var state = STARTED;
    for (var answer = 1; answer <= tooManyRequests; answer++) {
      var next = "answer-" + answer;
      wireMock.stubFor(
          get(urlPathEqualTo(path))
              .inScenario("rate limited " + path)
              .whenScenarioStateIs(state)
              .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
              .willSetStateTo(next));
      state = next;
    }

    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .inScenario("rate limited " + path)
            .whenScenarioStateIs(state)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/jpeg")
                    .withBody(createTestImage(600, 900))));
  }
}
