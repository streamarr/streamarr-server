package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.events.library.LibraryCountsChangedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Import(LibraryItemCountsIT.CommittedCounts.class)
class LibraryItemCountsIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraries;
  @Autowired private SeriesRepository series;
  @Autowired private SeasonRepository seasons;
  @Autowired private EpisodeRepository episodes;
  @Autowired private LetterIndexRecalculator recalculator;
  @Autowired private LibraryManagementService libraryManagement;
  @Autowired private TransactionTemplate transactions;
  @Autowired private CommittedCounts committedCounts;

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

  @TestConfiguration(proxyBeanMethods = false)
  static class CommittedCounts {

    final List<LibraryCountsChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommitted(LibraryCountsChangedEvent event) {
      events.add(event);
    }
  }
}
