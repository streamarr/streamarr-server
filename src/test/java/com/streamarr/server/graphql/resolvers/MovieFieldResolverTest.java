package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import com.streamarr.server.services.MovieService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {MovieFieldResolver.class, MovieResolver.class})
@DisplayName("Movie Field Resolver Tests")
class MovieFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private MovieService movieService;
  @MockitoBean private RatingRepository ratingRepository;
  @MockitoBean private ReviewRepository reviewRepository;

  private Movie setupMovie() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Inception").build();
    movie.setId(movieId);
    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));
    return movie;
  }

  @Test
  @DisplayName("Should return studios when movie queried with studios field")
  void shouldReturnStudiosWhenMovieQueriedWithStudiosField() {
    var movie = setupMovie();
    when(movieService.findStudios(movie.getId()))
        .thenReturn(List.of(Company.builder().name("Warner Bros").sourceId("wb").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { studios { name } } }", movie.getId()),
            "data.movie.studios[0].name");

    assertThat(name).isEqualTo("Warner Bros");
  }

  @Test
  @DisplayName("Should return cast when movie queried with cast field")
  void shouldReturnCastWhenMovieQueriedWithCastField() {
    var movie = setupMovie();
    when(movieService.findCast(movie.getId()))
        .thenReturn(List.of(Person.builder().name("Leonardo DiCaprio").sourceId("leo").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { cast { name } } }", movie.getId()),
            "data.movie.cast[0].name");

    assertThat(name).isEqualTo("Leonardo DiCaprio");
  }

  @Test
  @DisplayName("Should return directors when movie queried with directors field")
  void shouldReturnDirectorsWhenMovieQueriedWithDirectorsField() {
    var movie = setupMovie();
    when(movieService.findDirectors(movie.getId()))
        .thenReturn(List.of(Person.builder().name("Christopher Nolan").sourceId("nolan").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { directors { name } } }", movie.getId()),
            "data.movie.directors[0].name");

    assertThat(name).isEqualTo("Christopher Nolan");
  }

  @Test
  @DisplayName("Should return genres when movie queried with genres field")
  void shouldReturnGenresWhenMovieQueriedWithGenresField() {
    var movie = setupMovie();
    when(movieService.findGenres(movie.getId()))
        .thenReturn(List.of(Genre.builder().name("Sci-Fi").sourceId("scifi").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { genres { name } } }", movie.getId()),
            "data.movie.genres[0].name");

    assertThat(name).isEqualTo("Sci-Fi");
  }

  @Test
  @DisplayName("Should return ratings when movie queried with ratings field")
  void shouldReturnRatingsWhenMovieQueriedWithRatingsField() {
    var movie = setupMovie();
    when(ratingRepository.findByMovie_Id(movie.getId()))
        .thenReturn(List.of(Rating.builder().source("IMDb").value("8.8").build()));

    String source =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { ratings { source value } } }", movie.getId()),
            "data.movie.ratings[0].source");

    assertThat(source).isEqualTo("IMDb");
  }

  @Test
  @DisplayName("Should return reviews when movie queried with reviews field")
  void shouldReturnReviewsWhenMovieQueriedWithReviewsField() {
    var movie = setupMovie();
    when(reviewRepository.findByMovie_Id(movie.getId()))
        .thenReturn(List.of(Review.builder().author("Roger Ebert").build()));

    String author =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { reviews { author } } }", movie.getId()),
            "data.movie.reviews[0].author");

    assertThat(author).isEqualTo("Roger Ebert");
  }
}
