package com.streamarr.server.repositories.media;

import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ArtworkStepsAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository.SavedArtworkRequest;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.NewArtworkRequest;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that a library operation that takes over a target's artwork request leaves every final
 * result of the request's generation with the operation's id, whichever of the take-over and a
 * result write reaches the request first.
 */
@Tag("IntegrationTest")
@DisplayName("Artwork Request Take-Over Integration Tests")
class ArtworkRequestTakeOverIT extends AbstractIntegrationTest {

  private static final Instant REQUESTED_AT = Instant.parse("2026-10-15T10:00:00Z");

  @Autowired private ArtworkRequestRepository requests;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private ImageService imageService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final UUID endedOperation = UUID.randomUUID();
  private final UUID takingOverOperation = UUID.randomUUID();
  private ArtworkTarget movie;
  private ArtworkStepsAttempt attempt;

  @BeforeEach
  void setUp() {
    var movieId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
    movie = new ArtworkTarget(movieId, ImageEntityType.MOVIE);
    var generation = saveRequest(endedOperation).generation();
    attempt =
        ArtworkStepsAttempt.builder()
            .target(movie)
            .generation(generation)
            .attemptedAt(REQUESTED_AT)
            .libraryOperationId(Optional.of(endedOperation))
            .build();
  }

  @Test
  @DisplayName(
      "Should give the taking-over operation's id to a result that an attempt saves while the"
          + " take-over waits for the request")
  void shouldGiveTheTakingOverOperationsIdToAResultSavedWhileTheTakeOverWaits() throws Exception {
    var takeOver =
        whileHeldByAnotherTransaction(
            () -> itemResults.trySaveArtworkResults(attempt, List.of(unavailableBackdrop())),
            () -> saveRequest(takingOverOperation));

    assertThat(takeOver).isInstanceOf(SavedArtworkRequest.TakenOver.class);
    assertThat(artworkResults())
        .extracting(ItemResult::libraryOperationId)
        .containsExactly(Optional.of(takingOverOperation));
  }

  @Test
  @DisplayName(
      "Should give the taking-over operation's id to a saved image's result when the image waits"
          + " for the take-over to commit")
  void shouldGiveTheTakingOverOperationsIdToASavedImagesResultThatWaitsForTheTakeOver()
      throws Exception {
    var images =
        List.of(
            imageBuilder(movie.targetId())
                .id(UUID.randomUUID())
                .key("/poster.jpg")
                .path("movie/poster.jpg")
                .build());

    whileHeldByAnotherTransaction(
        () -> saveRequest(takingOverOperation),
        () -> {
          imageService.saveImages(images, attempt);
          return null;
        });

    assertThat(artworkResults())
        .extracting(ItemResult::imageType, ItemResult::libraryOperationId)
        .containsExactly(tuple(ImageType.POSTER, Optional.of(takingOverOperation)));
  }

  // The holder runs its work and keeps its transaction open until the contender waits behind it on
  // the request row, then commits. Returns what the contender returned.
  private <T> T whileHeldByAnotherTransaction(Callable<?> holderWork, Callable<T> contender)
      throws Exception {
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holderPid = new AtomicInteger();
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var holder =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        call(holderWork);
                        holderPid.set(dsl.fetchValue(DSL.field("pg_backend_pid()", Integer.class)));
                        held.countDown();
                        awaitLatch(release);
                      }));
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        var waiting = executor.submit(() -> transactionTemplate.execute(_ -> call(contender)));
        awaitWaitersBehind(jdbcTemplate, holderPid.get(), 1);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return waiting.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  private static <T> T call(Callable<T> work) {
    try {
      return work.call();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private SavedArtworkRequest saveRequest(UUID libraryOperationId) {
    return transactionTemplate.execute(
        _ ->
            requests.saveRequest(
                NewArtworkRequest.builder()
                    .artwork(
                        ArtworkSources.builder()
                            .targetId(movie.targetId())
                            .targetType(movie.targetType())
                            .sources(List.of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg")))
                            .build())
                    .refreshMode(ImageRefreshMode.PRESERVE)
                    .requestedAt(REQUESTED_AT)
                    .libraryOperationId(Optional.of(libraryOperationId))
                    .build()));
  }

  private ItemResult unavailableBackdrop() {
    return ItemResult.builder()
        .itemId(movie.targetId())
        .itemType(movie.targetType())
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.BACKDROP)
        .outcome(new ItemOutcome.Unavailable())
        .attemptedAt(attempt.attemptedAt())
        .artworkGeneration(attempt.generation())
        .build();
  }

  private List<ItemResult> artworkResults() {
    return itemResults.findByItem(movie.targetId(), movie.targetType()).stream()
        .filter(result -> result.step() == ItemStep.ARTWORK)
        .toList();
  }
}
