package com.streamarr.server.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.streamarr.server.fakes.TestImages.createTestImage;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.repositories.media.ItemResultRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Steps Integration Tests")
class ArtworkStepsIT extends AbstractWireMockIntegrationTest {

  @Autowired private ArtworkSteps artworkSteps;
  @Autowired private ArtworkRequestRepository requests;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private MovieService movieService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private ImageProperties imageProperties;

  private UUID entityId;
  private ArtworkItem movie;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    entityId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
    movie = new ArtworkItem(entityId, ImageEntityType.MOVIE);
  }

  @Test
  @DisplayName("Should save the image and its result for the request's generation")
  void shouldSaveTheImageAndItsResultForTheRequestsGeneration() {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);

    var outcome = artworkSteps.runSteps(movie);

    assertThat(outcome).isEqualTo(new ArtworkStepsOutcome.Finished(generation));
    assertThat(storedPosterKeys()).containsOnly("/poster.jpg");
    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
    assertThat(posterResult().artworkGeneration()).isEqualTo(generation);
  }

  @Test
  @DisplayName("Should run no step when the item has no artwork request")
  void shouldRunNoStepWhenTheItemHasNoArtworkRequest() {
    assertThat(artworkSteps.runSteps(movie)).isEqualTo(new ArtworkStepsOutcome.NotRequested());
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should delete written files and keep the step pending when the saved image's result cannot"
          + " be stored")
  void shouldDeleteWrittenFilesAndKeepTheStepPendingWhenTheSavedImagesResultCannotBeStored()
      throws IOException {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);

    var rejected = rejectSucceededResultsWhile(() -> artworkSteps.runSteps(movie));

    assertThat(rejected).isInstanceOf(ArtworkStepsOutcome.Unfinished.class);
    assertThat(storedPosterKeys()).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
    assertThat(posterResult().outcome())
        .isInstanceOfSatisfying(
            ItemOutcome.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.TEMPORARY));

    assertThat(artworkSteps.runSteps(movie))
        .isEqualTo(new ArtworkStepsOutcome.Finished(generation));
    assertThat(storedPosterKeys()).containsOnly("/poster.jpg");
    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
  }

  @Test
  @DisplayName(
      "Should save nothing when a newer generation replaces the attempt's while it waits to save")
  void shouldSaveNothingWhenANewerGenerationReplacesTheAttemptsWhileItWaitsToSave()
      throws Exception {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);

    try (var connection = dataSource.getConnection();
        var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      connection.setAutoCommit(false);
      try (var replace =
          connection.prepareStatement(
              "UPDATE artwork_request SET generation = nextval('artwork_request_generation')"
                  + " WHERE item_id = ?")) {
        replace.setObject(1, entityId);
        replace.executeUpdate();
      }

      var attempt = executor.submit(() -> artworkSteps.runSteps(movie));
      awaitWaitersBehind(jdbcTemplate, backendPid(connection), 1);
      connection.commit();

      assertThat(attempt.get(10, TimeUnit.SECONDS))
          .isEqualTo(new ArtworkStepsOutcome.Finished(generation));
    }

    assertThat(storedPosterKeys()).isEmpty();
    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
  }

  @Test
  @DisplayName("Should discard saved artwork when a delete of its movie commits first")
  void shouldDiscardSavedArtworkWhenADeleteOfItsMovieCommitsFirst() throws Exception {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);
    var deletePid = new CompletableFuture<Integer>();
    var commitDelete = new CountDownLatch(1);

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var delete =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        movieService.deleteMovieById(entityId);
                        deletePid.complete(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        awaitLatch(commitDelete);
                      }));
      var blockerPid = deletePid.get(10, TimeUnit.SECONDS);

      var attempt = executor.submit(() -> artworkSteps.runSteps(movie));
      awaitWaitersBehind(jdbcTemplate, blockerPid, 1);
      commitDelete.countDown();
      delete.get(10, TimeUnit.SECONDS);

      assertThat(attempt.get(10, TimeUnit.SECONDS))
          .isEqualTo(new ArtworkStepsOutcome.Finished(generation));
    }

    assertThat(storedPosterKeys()).isEmpty();
    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
  }

  @Test
  @DisplayName("Should delete saved artwork when it commits while a delete of its movie waits")
  void shouldDeleteSavedArtworkWhenItCommitsWhileADeleteOfItsMovieWaits() throws Exception {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);
    itemResults.trySave(
        ItemResult.builder()
            .itemId(entityId)
            .itemType(ImageEntityType.MOVIE)
            .step(ItemStep.ARTWORK)
            .imageType(ImageType.POSTER)
            .outcome(new ItemOutcome.Unavailable())
            .attemptedAt(Instant.parse("2026-09-23T10:00:00Z"))
            .artworkGeneration(0L)
            .build());
    var posterResult =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("item_result")
            .keyColumn("item_id")
            .rowId(entityId)
            .build();

    try (var resultLock = lockRow(posterResult);
        var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var attempt = executor.submit(() -> artworkSteps.runSteps(movie));
      awaitWaitersBehind(jdbcTemplate, resultLock.backendPid(), 1);
      var delete = executor.submit(() -> movieService.deleteMovieById(entityId));
      awaitWaitersBehind(jdbcTemplate, resultLock.backendPid(), 2);
      resultLock.release();

      assertThat(attempt.get(10, TimeUnit.SECONDS))
          .isEqualTo(new ArtworkStepsOutcome.Finished(generation));
      delete.get(10, TimeUnit.SECONDS);
    }

    assertThat(storedPosterKeys()).isEmpty();
    assertThat(itemResults.findByItem(entityId, ImageEntityType.MOVIE)).isEmpty();
    assertThat(storedArtworkFiles()).isEmpty();
  }

  @Test
  @DisplayName("Should store the image once when two attempts run the same steps at once")
  void shouldStoreTheImageOnceWhenTwoAttemptsRunTheSameStepsAtOnce() throws Exception {
    stubImage("/poster.jpg");
    var generation = request("/poster.jpg", ImageRefreshMode.PRESERVE);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> runStepsAfter(start));
      var second = executor.submit(() -> runStepsAfter(start));
      start.countDown();

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsOnly(new ArtworkStepsOutcome.Finished(generation));
    }

    assertThat(storedPosterKeys()).hasSize(ImageSize.values().length).containsOnly("/poster.jpg");
    assertThat(posterResult().outcome()).isEqualTo(new ItemOutcome.Succeeded());
    assertThat(storedArtworkFiles()).hasSize(ImageSize.values().length);
  }

  @Test
  @DisplayName("Should report artwork progress from stored requests and results after a restart")
  void shouldReportArtworkProgressFromStoredRequestsAndResultsAfterARestart() {
    stubImage("/poster.jpg");
    request("/poster.jpg", ImageRefreshMode.PRESERVE);
    var clock = new MutableClock(new AtomicReference<>(Instant.now()));
    var progressBeforeRestart = new ArtworkProgress(requests, itemResults, clock);
    progressBeforeRestart.reportProgress();

    var progressAfterRestart = new ArtworkProgress(requests, itemResults, clock);

    assertThat(progressAfterRestart.reportProgress())
        .filteredOn(report -> report.priority() == ArtworkPriority.PRIMARY)
        .singleElement()
        .satisfies(report -> assertThat(report.pending()).isPositive());
  }

  private ArtworkStepsOutcome runStepsAfter(CountDownLatch start) throws InterruptedException {
    start.await();
    return artworkSteps.runSteps(movie);
  }

  private long request(String key, ImageRefreshMode refreshMode) {
    return transactionTemplate.execute(
        _ -> requests.saveRequest(posterArtwork(key), refreshMode, Instant.now()).generation());
  }

  private ArtworkSources posterArtwork(String key) {
    return ArtworkSources.builder()
        .entityId(entityId)
        .entityType(ImageEntityType.MOVIE)
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, key)))
        .build();
  }

  private void stubImage(String key) {
    wireMock.stubFor(
        get(urlEqualTo(key))
            .willReturn(aResponse().withStatus(200).withBody(createTestImage(600, 900))));
  }

  private List<String> storedPosterKeys() {
    return imageRepository
        .findByEntityIdAndEntityTypeAndImageType(entityId, ImageEntityType.MOVIE, ImageType.POSTER)
        .stream()
        .map(Image::getKey)
        .toList();
  }

  private ItemResult posterResult() {
    return itemResults.findByItem(entityId, ImageEntityType.MOVIE).stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .filter(result -> result.imageType() == ImageType.POSTER)
        .findFirst()
        .orElseThrow();
  }

  private List<Path> storedArtworkFiles() throws IOException {
    var directory = Path.of(imageProperties.storagePath(), "movie", entityId.toString());
    if (Files.notExists(directory)) {
      return List.of();
    }

    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  private <T> T rejectSucceededResultsWhile(Supplier<T> action) {
    jdbcTemplate.execute(
        """
        CREATE FUNCTION reject_succeeded_item_result() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'simulated item result write failure';
        END
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER reject_succeeded_item_result
        BEFORE INSERT OR UPDATE ON item_result
        FOR EACH ROW WHEN (NEW.outcome = 'SUCCEEDED' AND NEW.item_id = '%s')
        EXECUTE FUNCTION reject_succeeded_item_result()
        """
            .formatted(entityId));
    try {
      return action.get();
    } finally {
      jdbcTemplate.execute("DROP TRIGGER reject_succeeded_item_result ON item_result");
      jdbcTemplate.execute("DROP FUNCTION reject_succeeded_item_result()");
    }
  }
}
