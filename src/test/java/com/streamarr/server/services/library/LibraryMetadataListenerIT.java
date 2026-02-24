package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.library.events.ItemProcessedEvent;
import com.streamarr.server.services.library.events.ScanCompletedEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Library Metadata Listener Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryMetadataListenerIT extends AbstractIntegrationTest {

  @Autowired private LibraryMetadataListener listener;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryMetadataRepository metadataRepository;

  private UUID libraryId;

  @BeforeAll
  void setup() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    var savedLibrary = libraryRepository.saveAndFlush(library);
    libraryId = savedLibrary.getId();

    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Batman").titleSort("Batman").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("123 Numbers")
            .titleSort("123 Numbers")
            .library(savedLibrary)
            .build());
  }

  @Test
  @DisplayName("Should calculate correct letter counts when scan completed")
  void shouldCalculateCorrectLetterCountsWhenScanCompleted() {
    listener.onScanCompleted(new ScanCompletedEvent(libraryId));

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);

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
        Movie.builder()
            .title("Delta")
            .titleSort("Delta")
            .library(savedIsolatedLibrary)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Echo")
            .titleSort("Echo")
            .library(savedIsolatedLibrary)
            .build());

    listener.onItemProcessed(new ItemProcessedEvent(savedIsolatedLibrary.getId()));

    var metadata =
        metadataRepository.findByLibraryIdOrderByLetterAsc(savedIsolatedLibrary.getId());

    assertThat(metadata).hasSize(2);
    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.D, AlphabetLetter.E);
    assertThat(metadata).extracting(LibraryMetadata::getItemCount).containsExactly(1, 1);
  }

  @Test
  @DisplayName("Should replace stale metadata on recalculation")
  void shouldReplaceStaleMetadataOnRecalculation() {
    metadataRepository.save(
        LibraryMetadata.builder()
            .libraryId(libraryId)
            .letter(AlphabetLetter.Z)
            .itemCount(99)
            .build());

    listener.onScanCompleted(new ScanCompletedEvent(libraryId));

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(libraryId);

    assertThat(metadata)
        .extracting(LibraryMetadata::getLetter)
        .containsExactly(AlphabetLetter.A, AlphabetLetter.B, AlphabetLetter.HASH);
    assertThat(metadata).extracting(LibraryMetadata::getItemCount).containsExactly(2, 1, 1);
  }

  @Test
  @DisplayName("Should bucket upper and lower case titles into same letter")
  void shouldBucketUpperAndLowerCaseTitlesIntoSameLetter() {
    var caseLibrary = LibraryFixtureCreator.buildFakeLibrary();
    var savedCaseLibrary = libraryRepository.saveAndFlush(caseLibrary);

    movieRepository.saveAndFlush(
        Movie.builder().title("alpha").titleSort("alpha").library(savedCaseLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(savedCaseLibrary).build());

    listener.onScanCompleted(new ScanCompletedEvent(savedCaseLibrary.getId()));

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

    listener.onScanCompleted(new ScanCompletedEvent(savedEmpty.getId()));

    var metadata = metadataRepository.findByLibraryIdOrderByLetterAsc(savedEmpty.getId());
    assertThat(metadata).isEmpty();
  }
}
