package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildCursorOptions;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static com.streamarr.server.services.pagination.PaginationDirection.FORWARD;
import static com.streamarr.server.services.pagination.PaginationDirection.REVERSE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Tag("IntegrationTest")
class MediaPaginationBoundaryIT extends AbstractIntegrationTest {
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private SeriesRepository series;
  @Autowired private MovieService movieService;
  @Autowired private SeriesService seriesService;

  @ParameterizedTest
  @MethodSource("deletedAnchors")
  @DisplayName("Should report the remaining boundaries when a cursor item was deleted")
  void shouldReportRemainingBoundariesWhenCursorItemWasDeleted(
      MediaType type, BoundaryCase scenario) {
    var library = createLibrary(type);
    var filter = MediaFilter.builder().libraryId(library.getId()).build();
    var initial = page(type, buildForwardOptions(3, filter));
    var anchor = initial.items().get(scenario.anchorIndex());
    remove(type, anchor.item().getId());

    var result = page(type, buildCursorOptions(3, scenario.direction(), anchor, filter));

    assertThat(result.items())
        .extracting(item -> item.item().getTitle())
        .containsExactlyElementsOf(scenario.expected().titles());
    assertThat(result.hasNextPage()).isEqualTo(scenario.expected().hasNext());
    assertThat(result.hasPreviousPage()).isEqualTo(scenario.expected().hasPrevious());
  }

  private static Stream<Arguments> deletedAnchors() {
    var cases =
        List.of(
            new BoundaryCase(
                FORWARD, 0, new ExpectedPage(List.of("Bravo", "Charlie"), false, false)),
            new BoundaryCase(FORWARD, 1, new ExpectedPage(List.of("Charlie"), false, true)),
            new BoundaryCase(FORWARD, 2, new ExpectedPage(List.of(), false, true)),
            new BoundaryCase(REVERSE, 0, new ExpectedPage(List.of(), true, false)),
            new BoundaryCase(REVERSE, 1, new ExpectedPage(List.of("Alpha"), true, false)),
            new BoundaryCase(
                REVERSE, 2, new ExpectedPage(List.of("Alpha", "Bravo"), false, false)));
    return Stream.of(MediaType.MOVIE, MediaType.SERIES)
        .flatMap(type -> cases.stream().map(scenario -> Arguments.of(type, scenario)));
  }

  private Library createLibrary(MediaType type) {
    var library =
        libraries.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().type(type).build());
    for (var title : List.of("Alpha", "Bravo", "Charlie")) {
      if (type == MediaType.MOVIE) {
        movies.saveAndFlush(Movie.builder().title(title).titleSort(title).library(library).build());
        continue;
      }

      series.saveAndFlush(Series.builder().title(title).titleSort(title).library(library).build());
    }

    return library;
  }

  private MediaPage<? extends BaseCollectable<?>> page(
      MediaType type, MediaPaginationOptions options) {
    if (type == MediaType.MOVIE) {
      return movieService.getMoviesWithFilter(options);
    }

    return seriesService.getSeriesWithFilter(options);
  }

  private void remove(MediaType type, UUID id) {
    if (type == MediaType.MOVIE) {
      movieService.deleteMovieById(id);
      return;
    }

    seriesService.deleteSeriesById(id);
  }

  private record BoundaryCase(
      PaginationDirection direction, int anchorIndex, ExpectedPage expected) {}

  private record ExpectedPage(List<String> titles, boolean hasNext, boolean hasPrevious) {}
}
