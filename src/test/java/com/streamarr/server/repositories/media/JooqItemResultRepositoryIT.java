package com.streamarr.server.repositories.media;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import com.streamarr.server.domain.media.ItemResult;
import com.streamarr.server.domain.media.ItemStep;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Item Result Repository Integration Tests")
class JooqItemResultRepositoryIT extends AbstractIntegrationTest {

  private static final Instant EARLIER = Instant.parse("2026-09-23T10:00:00Z");
  private static final Instant LATER = EARLIER.plus(1, ChronoUnit.MINUTES);

  @Autowired private ItemResultRepository itemResults;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;

  @Test
  @DisplayName("Should store failed artwork with its reason and source when an attempt fails")
  void shouldStoreFailedArtworkWithItsReasonAndSourceWhenAnAttemptFails() {
    var failure =
        artwork(savedMovieId(), ImageType.POSTER)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.DOWNLOAD_FAILED, "HTTP 503"))
            .sourceKey("/poster.jpg")
            .attemptedAt(EARLIER)
            .build();

    assertThat(itemResults.trySave(failure)).isTrue();

    assertThat(itemResults.findByItem(failure.itemId(), ImageEntityType.MOVIE))
        .containsExactly(failure);
  }

  @Test
  @DisplayName("Should resolve the failure when a later attempt succeeds")
  void shouldResolveTheFailureWhenALaterAttemptSucceeds() {
    var itemId = savedMovieId();
    itemResults.trySave(
        metadata(itemId)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .attemptedAt(EARLIER)
            .build());
    var success = metadata(itemId).outcome(new ItemOutcome.Succeeded()).attemptedAt(LATER).build();

    assertThat(itemResults.trySave(success)).isTrue();

    assertThat(itemResults.findByItem(itemId, ImageEntityType.MOVIE)).containsExactly(success);
  }

  @Test
  @DisplayName("Should reject a result when a later attempt has already been recorded")
  void shouldRejectAResultWhenALaterAttemptHasAlreadyBeenRecorded() {
    var itemId = savedMovieId();
    var success =
        artwork(itemId, ImageType.BACKDROP)
            .outcome(new ItemOutcome.Succeeded())
            .sourceKey("/new.jpg")
            .attemptedAt(LATER)
            .build();
    itemResults.trySave(success);

    var staleFailure =
        artwork(itemId, ImageType.BACKDROP)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.INVALID_MEDIA, "not an image"))
            .sourceKey("/old.jpg")
            .attemptedAt(EARLIER)
            .build();

    assertThat(itemResults.trySave(staleFailure)).isFalse();
    assertThat(itemResults.findByItem(itemId, ImageEntityType.MOVIE)).containsExactly(success);
  }

  @Test
  @DisplayName("Should keep one result per step and image type when an item has several")
  void shouldKeepOneResultPerStepAndImageTypeWhenAnItemHasSeveral() {
    var itemId = savedMovieId();
    var metadata = metadata(itemId).outcome(new ItemOutcome.Succeeded()).build();
    var poster = artwork(itemId, ImageType.POSTER).outcome(new ItemOutcome.Unavailable()).build();
    var backdrop = artwork(itemId, ImageType.BACKDROP).outcome(new ItemOutcome.Succeeded()).build();

    itemResults.trySave(metadata);
    itemResults.trySave(poster);
    itemResults.trySave(backdrop);
    itemResults.trySave(metadata.toBuilder().attemptedAt(LATER).build());

    assertThat(itemResults.findByItem(itemId, ImageEntityType.MOVIE))
        .containsExactlyInAnyOrder(
            metadata.toBuilder().attemptedAt(LATER).build(), poster, backdrop);
  }

  @Test
  @DisplayName("Should reject a stale failure that waits on a newer uncommitted success")
  void shouldRejectAStaleFailureThatWaitsOnANewerUncommittedSuccess() throws Exception {
    var itemId = savedMovieId();
    var success = metadata(itemId).outcome(new ItemOutcome.Succeeded()).attemptedAt(LATER).build();
    var staleFailure =
        metadata(itemId)
            .outcome(new ItemOutcome.Failed(ItemFailureReason.TEMPORARY, "timeout"))
            .attemptedAt(EARLIER)
            .build();
    var successWritten = new CountDownLatch(1);
    var commitSuccess = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var newer =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      _ -> {
                        var recorded = itemResults.trySave(success);
                        successWritten.countDown();
                        awaitLatch(commitSuccess);
                        return recorded;
                      }));
      assertThat(successWritten.await(10, TimeUnit.SECONDS)).isTrue();

      var older = executor.submit(() -> itemResults.trySave(staleFailure));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(hasWriterWaitingOnAnotherTransaction())
                      .as("the stale failure should wait for the uncommitted success")
                      .isTrue());
      commitSuccess.countDown();

      assertThat(newer.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(older.get(10, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(itemResults.findByItem(itemId, ImageEntityType.MOVIE)).containsExactly(success);
  }

  @Test
  @DisplayName("Should record nothing when the item does not exist")
  void shouldRecordNothingWhenTheItemDoesNotExist() {
    var missingItemId = UUID.randomUUID();
    var unavailable =
        artwork(missingItemId, ImageType.POSTER).outcome(new ItemOutcome.Unavailable()).build();

    assertThat(itemResults.trySave(unavailable)).isFalse();
    assertThat(itemResults.findByItem(missingItemId, ImageEntityType.MOVIE)).isEmpty();
  }

  @Test
  @DisplayName("Should record nothing when a delete of the item commits while the result waits")
  void shouldRecordNothingWhenADeleteOfTheItemCommitsWhileTheResultWaits() throws Exception {
    var itemId = savedMovieId();
    var success = metadata(itemId).outcome(new ItemOutcome.Succeeded()).build();
    var deletePid = new CompletableFuture<Integer>();
    var commitDelete = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var delete =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        jdbcTemplate.update("DELETE FROM movie WHERE id = ?", itemId);
                        deletePid.complete(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        awaitLatch(commitDelete);
                      }));
      var blockerPid = deletePid.get(10, TimeUnit.SECONDS);

      var write = executor.submit(() -> itemResults.trySave(success));
      awaitWaitersBehind(jdbcTemplate, blockerPid, 1);
      commitDelete.countDown();

      delete.get(10, TimeUnit.SECONDS);
      assertThat(write.get(10, TimeUnit.SECONDS)).isFalse();
    }

    assertThat(itemResults.findByItem(itemId, ImageEntityType.MOVIE)).isEmpty();
  }

  @Test
  @DisplayName("Should reject an overwritten result when its item does not exist")
  void shouldRejectAnOverwrittenResultWhenItsItemDoesNotExist() {
    var missingItemId = UUID.randomUUID();
    var success = metadata(missingItemId).outcome(new ItemOutcome.Succeeded()).build();

    assertThatThrownBy(() -> itemResults.overwrite(success))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(itemResults.findByItem(missingItemId, ImageEntityType.MOVIE)).isEmpty();
  }

  private UUID savedMovieId() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository
        .saveAndFlush(Movie.builder().title("Results").library(library).build())
        .getId();
  }

  private static ItemResult.ItemResultBuilder metadata(UUID itemId) {
    return ItemResult.builder()
        .itemId(itemId)
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.METADATA)
        .attemptedAt(EARLIER);
  }

  private static ItemResult.ItemResultBuilder artwork(UUID itemId, ImageType imageType) {
    return ItemResult.builder()
        .itemId(itemId)
        .itemType(ImageEntityType.MOVIE)
        .step(ItemStep.ARTWORK)
        .imageType(imageType)
        .attemptedAt(EARLIER);
  }

  private boolean hasWriterWaitingOnAnotherTransaction() {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE pid <> pg_backend_pid()
                AND wait_event_type = 'Lock'
                AND wait_event = 'transactionid'
                AND query ILIKE '%item_result%'
            )
            """,
            Boolean.class));
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("the racing transaction was never released");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while holding the racing transaction", exception);
    }
  }
}
