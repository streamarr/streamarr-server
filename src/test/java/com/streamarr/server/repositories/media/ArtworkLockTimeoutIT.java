package com.streamarr.server.repositories.media;

import static com.streamarr.server.fixtures.ImageFixture.imageBuilder;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ArtworkAttempt;
import com.streamarr.server.domain.media.ArtworkTarget;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.fixtures.ArtworkAttemptFixture;
import com.streamarr.server.fixtures.SavedMediaFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.ArtworkSources;
import com.streamarr.server.services.ImageService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Artwork Lock Timeout Integration Tests")
@SpringBootTest(properties = {"image.replacement-lock-timeout=200ms", "artwork.lock-timeout=200ms"})
class ArtworkLockTimeoutIT extends AbstractIntegrationTest {

  private static final Instant REQUESTED_AT = Instant.parse("2026-10-14T10:00:00Z");

  @Autowired private ArtworkRequestRepository requests;
  @Autowired private ItemResultRepository itemResults;
  @Autowired private ImageService imageService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private ArtworkTarget movie;

  @BeforeEach
  void setUp() {
    var movieId = SavedMediaFixture.saveMovie(libraryRepository, movieRepository).getId();
    movie = new ArtworkTarget(movieId, ImageEntityType.MOVIE);
  }

  @Test
  @DisplayName(
      "Should give up within the lock timeout when another transaction saves the target's request")
  void shouldGiveUpWithinTheLockTimeoutWhenAnotherTransactionSavesTheTargetsRequest()
      throws Exception {
    whileAnotherTransactionSavesARequest(
        () ->
            transactionTemplate.execute(
                _ ->
                    requests.saveRequest(
                        artwork("/other.jpg"), ImageRefreshMode.PRESERVE, REQUESTED_AT)));
  }

  @Test
  @DisplayName(
      "Should give up within the lock timeout when a completion waits for a request being saved")
  void shouldGiveUpWithinTheLockTimeoutWhenACompletionWaitsForARequestBeingSaved()
      throws Exception {
    whileAnotherTransactionSavesARequest(
        () -> transactionTemplate.execute(_ -> requests.lockPendingGeneration(movie)));
  }

  @Test
  @DisplayName(
      "Should give up within the lock timeout when results wait for a request being replaced")
  void shouldGiveUpWithinTheLockTimeoutWhenResultsWaitForARequestBeingReplaced() throws Exception {
    var attempt = ArtworkAttemptFixture.requestedAttemptFor(transactionTemplate, requests, movie);

    whileAnotherTransactionSavesARequest(
        () -> itemResults.trySaveArtworkResults(attempt, List.of(unavailablePoster(attempt))));
  }

  @Test
  @DisplayName(
      "Should give up within the lock timeout when images wait for a request being replaced")
  void shouldGiveUpWithinTheLockTimeoutWhenImagesWaitForARequestBeingReplaced() throws Exception {
    var attempt = ArtworkAttemptFixture.requestedAttemptFor(transactionTemplate, requests, movie);
    var images =
        List.of(imageBuilder(movie.targetId()).key("/poster.jpg").path("movie/poster.jpg").build());

    whileAnotherTransactionSavesARequest(
        () -> {
          imageService.saveImages(images, attempt);
          return null;
        });
  }

  @Test
  @DisplayName(
      "Should keep the caller's lock timeout when a request is saved in the caller's transaction")
  void shouldKeepTheCallersLockTimeoutWhenARequestIsSavedInTheCallersTransaction() {
    var lockTimeout =
        transactionTemplate.execute(
            _ -> {
              requests.saveRequest(artwork("/poster.jpg"), ImageRefreshMode.PRESERVE, REQUESTED_AT);
              return dsl.fetchValue("SELECT current_setting('lock_timeout')");
            });

    assertThat(lockTimeout).isEqualTo("0");
  }

  // Another transaction replaces the target's request and keeps it uncommitted while the contender
  // runs, so the contender waits for the request lock or the request row.
  private void whileAnotherTransactionSavesARequest(Callable<?> contender) throws Exception {
    var saved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var holder =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        requests.saveRequest(
                            artwork("/poster.jpg"), ImageRefreshMode.PRESERVE, REQUESTED_AT);
                        saved.countDown();
                        awaitLatch(release);
                      }));
      assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        var waiting = executor.submit(contender);

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasStackTraceContaining("lock timeout");
      } finally {
        release.countDown();
      }

      holder.get(10, TimeUnit.SECONDS);
    }
  }

  private ArtworkSources artwork(String posterKey) {
    return ArtworkSources.builder()
        .targetId(movie.targetId())
        .targetType(movie.targetType())
        .sources(List.of(new TmdbImageSource(ImageType.POSTER, posterKey)))
        .build();
  }

  private static ItemResult unavailablePoster(ArtworkAttempt attempt) {
    return ItemResult.builder()
        .itemId(attempt.target().targetId())
        .itemType(attempt.target().targetType())
        .step(ItemStep.ARTWORK)
        .imageType(ImageType.POSTER)
        .outcome(new ItemOutcome.Unavailable())
        .attemptedAt(attempt.attemptedAt())
        .artworkGeneration(attempt.generation())
        .build();
  }
}
