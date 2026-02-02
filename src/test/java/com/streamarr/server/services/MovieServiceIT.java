package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
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

  @BeforeAll
  public void setup() {

    var fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();

    var savedLibrary = libraryRepository.saveAndFlush(fakeLibrary);

    var fakeMovie1 = Movie.builder().title("fakeMovie").library(savedLibrary).build();

    var fakeMovie2 = Movie.builder().title("fakeMovie").library(savedLibrary).build();

    movieRepository.saveAllAndFlush(List.of(fakeMovie1, fakeMovie2));
  }

  @Test
  @DisplayName("Should limit first set of results to one when given 'first' argument and no cursor")
  void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

    var movies = movieService.getMoviesWithFilter(1, null, 0, null, null);

    assertThat(movies.getEdges().size()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
  void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

    var firstPageMovies = movieService.getMoviesWithFilter(1, null, 0, null, null);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges().size()).isEqualTo(1);

    var secondPageMovies = movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, null);

    assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isNotEqualByComparingTo(movie2.getNode().getId());
  }

  @Test
  @DisplayName(
      "Should paginate backward once, after getting first two results when given 'last' and 'before' arguments")
  void shouldPaginateBackwardWhenGivenLastAndCursor() {

    var firstPageMovies = movieService.getMoviesWithFilter(2, null, 0, null, null);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges().size()).isEqualTo(2);

    var secondPageMovies = movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), null);

    assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isEqualTo(movie2.getNode().getId());
  }
}
