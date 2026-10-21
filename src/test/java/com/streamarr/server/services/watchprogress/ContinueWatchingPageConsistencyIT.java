package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.SessionProgressFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepository;
import com.streamarr.server.repositories.streaming.ContinueWatchingRepositoryImpl;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Continue Watching page consistency")
@Import(ContinueWatchingPageConsistencyIT.ChangeBetweenReadsConfiguration.class)
class ContinueWatchingPageConsistencyIT extends AbstractIntegrationTest {

  private static final Instant NEWEST = Instant.parse("2026-10-19T12:00:00Z");

  @Autowired private ContinueWatchingService continueWatchingService;
  @Autowired private ChangeBetweenReads repository;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SessionProgressRepository progress;
  @Autowired private AuditColumnFixtures auditColumns;
  @Autowired private TransactionTemplate transactions;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    auth.deleteIdentity(identity);
  }

  @Test
  @DisplayName(
      "Should report a next page when items beyond the page remain after a deletion commits"
          + " between its reads")
  void shouldReportNextPageWhenItemsBeyondThePageRemainAfterADeletionCommitsBetweenItsReads() {
    identity = auth.createIdentity();
    var profileId = identity.profile().getId();
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var newestFirst =
        List.of("Alpha", "Bravo", "Charlie", "Delta").stream()
            .map(title -> movies.saveAndFlush(movieWithFile(library, title)))
            .toList();
    for (var age = 0; age < newestFirst.size(); age++) {
      saveProgress(profileId, newestFirst.get(age), NEWEST.minusSeconds(60L * age));
    }

    repository.commitAfterOrderedRead(
        () ->
            movies.deleteAllById(List.of(newestFirst.get(1).getId(), newestFirst.get(2).getId())));
    var page = continueWatchingService.getPage(profileId, firstPage(profileId));

    assertThat(page.items())
        .extracting(item -> item.item().getId())
        .containsExactly(newestFirst.get(0).getId());
    assertThat(page.hasNextPage()).isTrue();
  }

  private static MediaPaginationOptions firstPage(UUID profileId) {
    return ContinueWatchingService.listOptions(profileId).toBuilder()
        .paginationOptions(new PaginationService().getPaginationOptions(1, null, 0, null))
        .build();
  }

  private static Movie movieWithFile(Library library, String title) {
    return Movie.builder()
        .title(title)
        .titleSort(title)
        .library(library)
        .files(
            Set.of(
                MediaFile.builder()
                    .libraryId(library.getId())
                    .status(MediaFileStatus.MATCHED)
                    .filename(UUID.randomUUID() + ".mkv")
                    .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                    .build()))
        .build();
  }

  private void saveProgress(UUID profileId, Movie movie, Instant lastModifiedOn) {
    var saved =
        progress.saveAndFlush(
            SessionProgressFixture.progressBuilder(
                    profileId, movie.getFiles().iterator().next().getId())
                .positionSeconds(600)
                .build());
    auditColumns.overwriteLastModifiedOn(
        SESSION_PROGRESS, SESSION_PROGRESS.SESSION_ID.eq(saved.getSessionId()), lastModifiedOn);
  }

  @TestConfiguration
  static class ChangeBetweenReadsConfiguration {
    @Bean
    @Primary
    ChangeBetweenReads changeBetweenReads(
        ContinueWatchingRepositoryImpl delegate, TransactionTemplate transactions) {
      return new ChangeBetweenReads(delegate, transactions);
    }
  }

  /** Commits a change on another connection after the ordered read of the next page. */
  @RequiredArgsConstructor
  static final class ChangeBetweenReads implements ContinueWatchingRepository {
    private final ContinueWatchingRepository delegate;
    private final TransactionTemplate transactions;
    private final AtomicReference<Runnable> change = new AtomicReference<>();

    void commitAfterOrderedRead(Runnable committedChange) {
      change.set(committedChange);
    }

    @Override
    public List<PageItem<UUID>> findItems(UUID profileId, MediaPaginationOptions options) {
      var items = delegate.findItems(profileId, options);
      var pending = change.getAndSet(null);
      if (pending == null) {
        return items;
      }

      try (var executor = SystemWork.newExecutorRunningAsSystemActor("change-between-reads-")) {
        CompletableFuture.runAsync(
                () -> transactions.executeWithoutResult(_ -> pending.run()), executor)
            .join();
      }

      return items;
    }

    @Override
    public Map<UUID, Instant> findMatchingItems(UUID profileId, Set<UUID> itemIds) {
      return delegate.findMatchingItems(profileId, itemIds);
    }
  }
}
