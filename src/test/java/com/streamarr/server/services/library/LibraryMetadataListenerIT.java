package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Library Metadata Listener Integration Tests")
class LibraryMetadataListenerIT extends AbstractIntegrationTest {

  @Autowired private LibraryMetadataListener listener;

  @Autowired private LetterIndexRecalculator letterIndexRecalculator;

  @Autowired private LibraryOperationRepository libraryOperations;

  @Autowired private TransactionTemplate transactionTemplate;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryMetadataRepository metadataRepository;

  @Autowired private LibraryOperationExecution libraryOperationExecution;

  @Test
  @DisplayName("Should calculate correct letter counts when the letter index is recalculated")
  void shouldCalculateCorrectLetterCountsWhenTheLetterIndexIsRecalculated() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Batman").titleSort("Batman").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("123 Numbers").titleSort("123 Numbers").library(library).build());

    letterIndexRecalculator.recalculateLetterIndex(library.getId());

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(library.getId());

    assertThat(metadata).hasSize(3);
    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.A, AlphabetLetter.B, AlphabetLetter.HASH);
    assertThat(metadata).extracting(LibraryMetadata::getItemCount).containsExactly(2, 1, 1);
  }

  @Test
  @DisplayName("Should trigger recalculation when item processed and not scanning")
  void shouldTriggerRecalculationWhenItemProcessedAndNotScanning() {
    var isolatedLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedIsolatedLibrary = libraryRepository.saveAndFlush(isolatedLibrary);

    movieRepository.saveAndFlush(
        Movie.builder().title("Delta").titleSort("Delta").library(savedIsolatedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Echo").titleSort("Echo").library(savedIsolatedLibrary).build());

    listener.onItemProcessed(new ItemProcessedEvent(savedIsolatedLibrary.getId()));

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(savedIsolatedLibrary.getId());

    assertThat(metadata).hasSize(2);
    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.D, AlphabetLetter.E);
    assertThat(metadata).extracting(LibraryMetadata::getItemCount).containsExactly(1, 1);
  }

  @Test
  @DisplayName(
      "Should count an item processed during a refresh in the letter index when the refresh ends")
  void shouldCountAnItemProcessedDuringARefreshInTheLetterIndexWhenTheRefreshEnds() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var refreshId = storeRefresh(library.getId());
    movieRepository.saveAndFlush(
        Movie.builder().title("Foxtrot").titleSort("Foxtrot").library(library).build());
    listener.onItemProcessed(new ItemProcessedEvent(library.getId()));

    SystemWork.runAsSystemActor(() -> libraryOperationExecution.execute(refreshId));

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(library.getId());
    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactly(tuple(AlphabetLetter.F, 1));
  }

  @Test
  @DisplayName("Should replace stale metadata when recalculation is triggered")
  void shouldReplaceStaleMetadataOnRecalculation() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Batman").titleSort("Batman").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("123 Numbers").titleSort("123 Numbers").library(library).build());

    metadataRepository.save(
        LibraryMetadata.builder()
            .libraryId(library.getId())
            .letter(AlphabetLetter.Z)
            .itemCount(99)
            .build());

    letterIndexRecalculator.recalculateLetterIndex(library.getId());

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(library.getId());

    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.A, AlphabetLetter.B, AlphabetLetter.HASH);
    assertThat(metadata).extracting(LibraryMetadata::getItemCount).containsExactly(2, 1, 1);
  }

  @Test
  @DisplayName("Should bucket mixed case titles into same letter when titles differ only by case")
  void shouldBucketUpperAndLowerCaseTitlesIntoSameLetter() {
    var caseLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedCaseLibrary = libraryRepository.saveAndFlush(caseLibrary);

    movieRepository.saveAndFlush(
        Movie.builder().title("alpha").titleSort("alpha").library(savedCaseLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(savedCaseLibrary).build());

    letterIndexRecalculator.recalculateLetterIndex(savedCaseLibrary.getId());

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(savedCaseLibrary.getId());

    assertThat(metadata).hasSize(1);
    assertThat(metadata.getFirst().getLetter()).isEqualTo(AlphabetLetter.A);
    assertThat(metadata.getFirst().getItemCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should produce empty metadata when library has no items")
  void shouldProduceEmptyMetadataWhenLibraryHasNoItems() {
    var emptyLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedEmpty = libraryRepository.saveAndFlush(emptyLibrary);

    metadataRepository.save(
        LibraryMetadata.builder()
            .libraryId(savedEmpty.getId())
            .letter(AlphabetLetter.A)
            .itemCount(99)
            .build());

    letterIndexRecalculator.recalculateLetterIndex(savedEmpty.getId());

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(savedEmpty.getId());
    assertThat(metadata).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count an item renamed while the end of a library operation holds the library's row"
          + " once the end commits")
  void shouldCountAnItemRenamedWhileTheEndOfALibraryOperationHoldsTheLibrarysRow()
      throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    var operationId = storeRefresh(library.getId());
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var endPid = new AtomicInteger();
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var end =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        assertThat(libraryOperations.tryLockLibraryOfLibraryOperation(operationId))
                            .isTrue();
                        letterIndexRecalculator.recalculateLetterIndex(library.getId());
                        endPid.set(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        held.countDown();
                        awaitLatch(release);
                        assertThat(libraryOperations.tryEndLibraryOperation(endOf(operationId)))
                            .isTrue();
                      }));
      try {
        awaitLatch(held);
        renameMovie(movie.getId(), "Zulu");
        var listening =
            executor.submit(
                () -> listener.onItemProcessed(new ItemProcessedEvent(library.getId())));
        awaitWaitersBehind(jdbcTemplate, endPid.get(), 1);
        release.countDown();
        end.get(10, TimeUnit.SECONDS);
        listening.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }

    assertThat(metadataRepository.findByLibraryIdOrderByLetterAsc(library.getId()))
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.Z);
  }

  @Test
  @DisplayName("Should skip recalculation when an item is processed while a library operation runs")
  void shouldSkipRecalculationWhenAnItemIsProcessedWhileALibraryOperationRuns() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    storeRefresh(library.getId());

    listener.onItemProcessed(new ItemProcessedEvent(library.getId()));

    assertThat(metadataRepository.findByLibraryIdOrderByLetterAsc(library.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should skip recalculation when the library of a processed item no longer exists")
  void shouldSkipRecalculationWhenTheLibraryOfAProcessedItemNoLongerExists() {
    var libraryId = UUID.randomUUID();

    listener.onItemProcessed(new ItemProcessedEvent(libraryId));

    assertThat(metadataRepository.findByLibraryIdOrderByLetterAsc(libraryId)).isEmpty();
  }

  private UUID storeRefresh(UUID libraryId) {
    var outcome =
        transactionTemplate.execute(
            _ ->
                libraryOperations.storeLibraryOperation(
                    NewLibraryOperation.refresh(libraryId, ImageRefreshMode.PRESERVE)));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored.id();
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The refresh was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  private void renameMovie(UUID movieId, String titleSort) {
    transactionTemplate.executeWithoutResult(
        _ ->
            jdbcTemplate.update(
                "UPDATE base_collectable SET title_sort = ? WHERE id = ?", titleSort, movieId));
  }

  private static LibraryOperationEnd endOf(UUID libraryOperationId) {
    return LibraryOperationEnd.builder()
        .libraryOperationId(libraryOperationId)
        .conclusion(LibraryOperationConclusion.FINISHED)
        .endedAt(Instant.now())
        .build();
  }
}
