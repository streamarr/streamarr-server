package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryOperationRepository;
import com.streamarr.server.repositories.LibraryOperationRepository.StoreOutcome;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.events.library.ItemProcessedEvent;
import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Import(LibraryItemCountsIT.CommittedCounts.class)
@Tag("IntegrationTest")
class LibraryItemCountsIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraries;
  @Autowired private SeriesRepository series;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private LetterIndexRecalculator recalculator;
  @Autowired private LibraryManagementService libraryManagement;
  @Autowired private TransactionTemplate transactions;
  @Autowired private CommittedCounts committedCounts;
  @Autowired private MovieRepository movies;
  @Autowired private MediaFileRepository mediaFiles;
  @Autowired private LibraryMetadataListener itemProcessing;
  @Autowired private LibraryOperationRepository libraryOperations;
  @Autowired private LibraryOperationExecution libraryOperationExecution;
  @Autowired private LibraryCountsService counts;

  @Test
  void shouldCountOnlySeriesWhenALibraryContainsSeasonsAndEpisodes() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var show =
        series.saveAndFlush(
            Series.builder().title("Alpha").titleSort("Alpha").library(library).build());
    var season =
        seasons.saveAndFlush(
            Season.builder()
                .title("Season One")
                .titleSort("Season One")
                .seasonNumber(1)
                .series(show)
                .library(library)
                .build());
    episodes.saveAndFlush(
        Episode.builder()
            .title("Pilot")
            .titleSort("Pilot")
            .episodeNumber(1)
            .season(season)
            .library(library)
            .build());

    recalculator.recalculateLetterIndex(library.getId());

    assertThat(libraryManagement.getAlphabetIndex(library.getId()))
        .extracting(LibraryMetadata::getLetter, LibraryMetadata::getItemCount)
        .containsExactly(tuple(AlphabetLetter.A, 1));
  }

  @Test
  void shouldPublishTheRecountOnlyAfterCommitAndNeverAfterRollback() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var change = new LibraryCountsChangedEvent(library.getId());

    transactions.executeWithoutResult(
        status -> {
          recalculator.recalculateLetterIndex(library.getId());
          assertThat(committedCounts.events).doesNotContain(change);
          status.setRollbackOnly();
        });
    assertThat(committedCounts.events).doesNotContain(change);

    transactions.executeWithoutResult(
        _ -> {
          recalculator.recalculateLetterIndex(library.getId());
          assertThat(committedCounts.events).doesNotContain(change);
        });
    assertThat(committedCounts.events).contains(change);
  }

  @Test
  @DisplayName("Should count a movie once when it has several files")
  void shouldCountAMovieOnceWhenItHasSeveralFiles() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movies.saveAndFlush(
            Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    for (var filename : List.of("Alpha (2001) - 1080p.mkv", "Alpha (2001) - 2160p.mkv")) {
      mediaFiles.saveAndFlush(
          MediaFile.builder()
              .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
              .filename(filename)
              .status(MediaFileStatus.MATCHED)
              .libraryId(library.getId())
              .mediaId(movie.getId())
              .build());
    }

    recalculator.recalculateLetterIndex(library.getId());

    assertThat(totalOf(library)).isOne();
  }

  @Test
  @DisplayName(
      "Should keep the previous total while a scan runs and count its items when the scan fails")
  void shouldKeepThePreviousTotalWhileAScanRunsAndCountItsItemsWhenTheScanFails() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movies.saveAndFlush(Movie.builder().title("Alpha").titleSort("Alpha").library(library).build());
    recalculator.recalculateLetterIndex(library.getId());
    var scanId = storeScan(library.getId());
    movies.saveAndFlush(Movie.builder().title("Bravo").titleSort("Bravo").library(library).build());
    itemProcessing.onItemProcessed(new ItemProcessedEvent(library.getId()));
    var totalWhileScanning = totalOf(library);

    SystemWork.runAsSystemActor(() -> libraryOperationExecution.execute(scanId));

    assertThat(totalWhileScanning).isOne();
    assertThat(libraries.findById(library.getId()).orElseThrow().getStatus())
        .isEqualTo(LibraryStatus.UNHEALTHY);
    assertThat(totalOf(library)).isEqualTo(2);
  }

  private int totalOf(Library library) {
    return counts.getItemCounts(Set.of(library.getId())).get(library.getId()).total();
  }

  private UUID storeScan(UUID libraryId) {
    var outcome =
        transactions.execute(
            _ -> libraryOperations.storeLibraryOperation(NewLibraryOperation.scan(libraryId)));
    return switch (outcome) {
      case StoreOutcome.Stored(var stored, _) -> stored.id();
      case StoreOutcome.Rejected(var rejection) ->
          throw new AssertionError("The scan was not stored: " + rejection);
      case null -> throw new AssertionError("The store returned nothing");
    };
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CommittedCounts {

    final List<LibraryCountsChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommitted(LibraryCountsChangedEvent event) {
      events.add(event);
    }
  }
}
