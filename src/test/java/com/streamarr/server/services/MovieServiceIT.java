package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MovieServiceIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieService movieService;

  private Library savedLibraryA;
  private Library savedLibraryB;

  @BeforeAll
  public void setup() {

    var libraryA = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryA = libraryRepository.saveAndFlush(libraryA);

    var libraryB = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryB = libraryRepository.saveAndFlush(libraryB);

    var movieA1 = Movie.builder().title("Alpha").library(savedLibraryA).build();
    var movieA2 = Movie.builder().title("Beta").library(savedLibraryA).build();
    var movieB1 = Movie.builder().title("Gamma").library(savedLibraryB).build();

    movieRepository.saveAllAndFlush(List.of(movieA1, movieA2, movieB1));
  }

  private MediaFilter filterForLibrary(Library library) {
    return MediaFilter.builder().libraryId(library.getId()).build();
  }

  @Test
  @DisplayName("Should limit first set of results to one when given 'first' argument and no cursor")
  void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

    var filter = filterForLibrary(savedLibraryA);
    var movies = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    assertThat(movies.getEdges().size()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
  void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPageMovies = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges().size()).isEqualTo(1);

    var secondPageMovies =
        movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, filter);

    assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isNotEqualByComparingTo(movie2.getNode().getId());
  }

  @Test
  @DisplayName(
      "Should paginate backward once, after getting first two results when given 'last' and 'before' arguments")
  void shouldPaginateBackwardWhenGivenLastAndCursor() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPageMovies = movieService.getMoviesWithFilter(2, null, 0, null, filter);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges().size()).isEqualTo(2);

    var secondPageMovies =
        movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), filter);

    assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isEqualTo(movie2.getNode().getId());
  }

  @Test
  @DisplayName("Should return only movies from specified library when libraryId filter is set")
  void shouldReturnOnlyMoviesFromSpecifiedLibraryWhenLibraryIdFilterSet() {

    var filterA = filterForLibrary(savedLibraryA);
    var filterB = filterForLibrary(savedLibraryB);

    var libraryAMovies = movieService.getMoviesWithFilter(10, null, 0, null, filterA);
    var libraryBMovies = movieService.getMoviesWithFilter(10, null, 0, null, filterB);

    assertThat(libraryAMovies.getEdges()).hasSize(2);
    assertThat(libraryBMovies.getEdges()).hasSize(1);
  }

  @Test
  @DisplayName("Should paginate forward within library scope")
  void shouldPaginateForwardWithinLibraryScope() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(1);

    var endCursor = firstPage.getPageInfo().getEndCursor();
    var secondPage = movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(1);

    var allTitles =
        List.of(
            firstPage.getEdges().get(0).getNode().getTitle(),
            secondPage.getEdges().get(0).getNode().getTitle());

    assertThat(allTitles).containsExactlyInAnyOrder("Alpha", "Beta");
  }

  @Test
  @DisplayName("Should paginate backward within library scope")
  void shouldPaginateBackwardWithinLibraryScope() {

    var filter = filterForLibrary(savedLibraryA);

    var allMovies = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(allMovies.getEdges()).hasSize(2);

    var endCursor = allMovies.getPageInfo().getEndCursor();
    var lastOne = movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), filter);
    assertThat(lastOne.getEdges()).hasSize(1);

    var title = lastOne.getEdges().get(0).getNode().getTitle();
    assertThat(title).isEqualTo("Alpha");
  }

  @Test
  @DisplayName("Should reject cursor when libraryId does not match")
  void shouldRejectCursorWhenLibraryIdMismatch() {

    var filterA = filterForLibrary(savedLibraryA);
    var filterB = filterForLibrary(savedLibraryB);

    var libraryAMovies = movieService.getMoviesWithFilter(1, null, 0, null, filterA);
    var cursorFromLibraryA = libraryAMovies.getPageInfo().getEndCursor().getValue();

    assertThatThrownBy(
            () -> movieService.getMoviesWithFilter(1, cursorFromLibraryA, 0, null, filterB))
        .isInstanceOf(InvalidCursorException.class);
  }
}
