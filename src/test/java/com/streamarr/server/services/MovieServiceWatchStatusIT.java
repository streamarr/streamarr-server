package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Movie Service Watch Status Filter Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovieServiceWatchStatusIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private MovieService movieService;
  @Autowired private DSLContext dsl;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Library library;
  private Movie watchedMovie;
  private Movie inProgressMovie;

  @BeforeAll
  void setup() {
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    watchedMovie = createMovieWithFile("Watched Movie");
    inProgressMovie = createMovieWithFile("In Progress Movie");
    createMovieWithFile("Unwatched Movie");

    // Mark watchedMovie as WATCHED via watch_history
    watchHistoryRepository.saveAndFlush(
        WatchHistory.builder()
            .userId(USER_ID)
            .collectableId(watchedMovie.getId())
            .watchedAt(Instant.now())
            .durationSeconds(7200)
            .build());

    // Mark inProgressMovie as IN_PROGRESS via session_progress
    var inProgressFileId = inProgressMovie.getFiles().iterator().next().getId();
    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(inProgressFileId)
            .positionSeconds(1800)
            .percentComplete(25.0)
            .durationSeconds(7200)
            .build());

    // unwatchedMovie has no watch_history or session_progress — stays UNWATCHED
  }

  private Movie createMovieWithFile(String title) {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename(title.toLowerCase().replace(' ', '-') + ".mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();

    return movieRepository.saveAndFlush(
        Movie.builder().title(title).titleSort(title).files(Set.of(file)).library(library).build());
  }

  @Nested
  @DisplayName("Watch Status Filter")
  class WatchStatusFilterTests {

    @Test
    @DisplayName("Should return only watched movies when filtering by WATCHED status")
    void shouldReturnOnlyWatchedMoviesWhenFilteringByWatchedStatus() {
      var filter =
          MediaFilter.builder().libraryId(library.getId()).watchStatus(WatchStatus.WATCHED).build();

      var page = movieService.getMoviesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactly("Watched Movie");
    }

    @Test
    @DisplayName("Should return only in-progress movies when filtering by IN_PROGRESS status")
    void shouldReturnOnlyInProgressMoviesWhenFilteringByInProgressStatus() {
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .watchStatus(WatchStatus.IN_PROGRESS)
              .build();

      var page = movieService.getMoviesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactly("In Progress Movie");
    }

    @Test
    @DisplayName("Should return only unwatched movies when filtering by UNWATCHED status")
    void shouldReturnOnlyUnwatchedMoviesWhenFilteringByUnwatchedStatus() {
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .watchStatus(WatchStatus.UNWATCHED)
              .build();

      var page = movieService.getMoviesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items())
          .extracting(item -> item.item().getTitle())
          .containsExactly("Unwatched Movie");
    }

    @Test
    @DisplayName("Should return all movies when no watch status filter specified")
    void shouldReturnAllMoviesWhenNoWatchStatusFilterSpecified() {
      var filter = MediaFilter.builder().libraryId(library.getId()).build();

      var page = movieService.getMoviesWithFilter(buildForwardOptions(20, filter));

      assertThat(page.items()).hasSize(3);
    }
  }

  @Nested
  @DisplayName("Last Watched Sort")
  class LastWatchedSortTests {

    @Test
    @DisplayName("Should return movies ordered by most recently watched DESC")
    void shouldReturnMoviesOrderedByMostRecentlyWatchedDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.LAST_WATCHED)
              .sortDirection(SortOrder.DESC)
              .build();

      var page = movieService.getMoviesWithFilter(buildForwardOptions(20, filter));

      // inProgressMovie has session_progress (most recent activity).
      // watchedMovie has no session_progress (only watch_history — not used for sort).
      // unwatchedMovie has no activity at all.
      // Both nulls sort last under nullsLast; order between them is not guaranteed.
      var titles = page.items().stream().map(item -> item.item().getTitle()).toList();
      assertThat(titles).hasSize(3);
      assertThat(titles.getFirst()).isEqualTo("In Progress Movie");
      assertThat(titles.subList(1, 3))
          .containsExactlyInAnyOrder("Watched Movie", "Unwatched Movie");
    }

    @Test
    @DisplayName("Should paginate forward using cursor when sorted by LAST_WATCHED DESC")
    void shouldPaginateForwardUsingCursorWhenSortedByLastWatchedDesc() {
      var paginationLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      var baseline = Instant.parse("2026-01-01T00:00:00Z");
      var first = createMovieWithSessionProgress(paginationLibrary, "First");
      var second = createMovieWithSessionProgress(paginationLibrary, "Second");
      var third = createMovieWithSessionProgress(paginationLibrary, "Third");
      pinSessionProgressTimestamp(first, baseline);
      pinSessionProgressTimestamp(second, baseline.plusSeconds(1));
      pinSessionProgressTimestamp(third, baseline.plusSeconds(2));

      var filter =
          MediaFilter.builder()
              .libraryId(paginationLibrary.getId())
              .sortBy(OrderMediaBy.LAST_WATCHED)
              .sortDirection(SortOrder.DESC)
              .build();

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).hasSize(1);
      assertThat(page1.items().getFirst().item().getId()).isEqualTo(third.getId());
      assertThat(page1.hasNextPage()).isTrue();

      var page2 =
          movieService.getMoviesWithFilter(
              buildForwardContinuation(1, filter, page1.items().getLast()));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.items().getFirst().item().getId()).isEqualTo(second.getId());
      assertThat(page2.hasNextPage()).isTrue();

      var page3 =
          movieService.getMoviesWithFilter(
              buildForwardContinuation(1, filter, page2.items().getLast()));
      assertThat(page3.items()).hasSize(1);
      assertThat(page3.items().getFirst().item().getId()).isEqualTo(first.getId());
      assertThat(page3.hasNextPage()).isFalse();

      var allIds =
          Stream.of(page1, page2, page3)
              .flatMap(p -> p.items().stream())
              .map(pi -> pi.item().getId())
              .toList();
      assertThat(allIds).doesNotHaveDuplicates();
    }

    private void pinSessionProgressTimestamp(Movie movie, Instant timestamp) {
      var mediaFileId = movie.getFiles().iterator().next().getId();
      dsl.update(Tables.SESSION_PROGRESS)
          .set(Tables.SESSION_PROGRESS.LAST_MODIFIED_ON, timestamp.atOffset(ZoneOffset.UTC))
          .where(Tables.SESSION_PROGRESS.MEDIA_FILE_ID.eq(mediaFileId))
          .execute();
    }

    private Movie createMovieWithSessionProgress(Library lib, String title) {
      var file =
          MediaFile.builder()
              .libraryId(lib.getId())
              .status(MediaFileStatus.MATCHED)
              .filename(title.toLowerCase() + ".mkv")
              .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
              .build();

      var movie =
          movieRepository.saveAndFlush(
              Movie.builder()
                  .title(title)
                  .titleSort(title)
                  .files(Set.of(file))
                  .library(lib)
                  .build());

      sessionProgressRepository.saveAndFlush(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(movie.getFiles().iterator().next().getId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build());

      return movie;
    }
  }
}
