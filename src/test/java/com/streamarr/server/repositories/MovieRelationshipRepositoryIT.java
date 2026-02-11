package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Movie Relationship Repository Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MovieRelationshipRepositoryIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private GenreRepository genreRepository;
  @Autowired private RatingRepository ratingRepository;
  @Autowired private ReviewRepository reviewRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  private Movie savedMovie;

  @BeforeAll
  void setup() {
    Library library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());

    var actor =
        personRepository.save(
            Person.builder().name("Leonardo DiCaprio").sourceId("actor-1").build());
    var director =
        personRepository.save(Person.builder().name("Christopher Nolan").sourceId("dir-1").build());
    var studio =
        companyRepository.save(Company.builder().name("Warner Bros").sourceId("wb-1").build());
    var genre = genreRepository.save(Genre.builder().name("Sci-Fi").sourceId("scifi-1").build());

    savedMovie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Inception")
                .library(library)
                .studios(Set.of(studio))
                .cast(List.of(actor))
                .directors(List.of(director))
                .genres(Set.of(genre))
                .build());

    ratingRepository.save(Rating.builder().movie(savedMovie).source("IMDb").value("8.8").build());
    reviewRepository.save(Review.builder().movie(savedMovie).author("Roger Ebert").build());
  }

  @Test
  @DisplayName("Should find studios by movie ID")
  void shouldFindStudiosByMovieId() {
    var studios = companyRepository.findByMovieId(savedMovie.getId());

    assertThat(studios).hasSize(1);
    assertThat(studios.getFirst().getName()).isEqualTo("Warner Bros");
  }

  @Test
  @DisplayName("Should find cast by movie ID in order")
  void shouldFindCastByMovieIdInOrder() {
    var cast = personRepository.findCastByMovieId(savedMovie.getId());

    assertThat(cast).hasSize(1);
    assertThat(cast.getFirst().getName()).isEqualTo("Leonardo DiCaprio");
  }

  @Test
  @DisplayName("Should find directors by movie ID in order")
  void shouldFindDirectorsByMovieIdInOrder() {
    var directors = personRepository.findDirectorsByMovieId(savedMovie.getId());

    assertThat(directors).hasSize(1);
    assertThat(directors.getFirst().getName()).isEqualTo("Christopher Nolan");
  }

  @Test
  @DisplayName("Should find genres by movie ID")
  void shouldFindGenresByMovieId() {
    var genres = genreRepository.findByMovieId(savedMovie.getId());

    assertThat(genres).hasSize(1);
    assertThat(genres.getFirst().getName()).isEqualTo("Sci-Fi");
  }

  @Test
  @DisplayName("Should find ratings by movie ID")
  void shouldFindRatingsByMovieId() {
    var ratings = ratingRepository.findByMovie_Id(savedMovie.getId());

    assertThat(ratings).hasSize(1);
    assertThat(ratings.getFirst().getSource()).isEqualTo("IMDb");
  }

  @Test
  @DisplayName("Should find reviews by movie ID")
  void shouldFindReviewsByMovieId() {
    var reviews = reviewRepository.findByMovie_Id(savedMovie.getId());

    assertThat(reviews).hasSize(1);
    assertThat(reviews.getFirst().getAuthor()).isEqualTo("Roger Ebert");
  }

  @Test
  @DisplayName("Should return empty collections when movie has no relationships")
  void shouldReturnEmptyWhenMovieHasNoRelationships() {
    Library library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var emptyMovie =
        movieRepository.saveAndFlush(Movie.builder().title("Empty Movie").library(library).build());

    assertThat(companyRepository.findByMovieId(emptyMovie.getId())).isEmpty();
    assertThat(personRepository.findCastByMovieId(emptyMovie.getId())).isEmpty();
    assertThat(personRepository.findDirectorsByMovieId(emptyMovie.getId())).isEmpty();
    assertThat(genreRepository.findByMovieId(emptyMovie.getId())).isEmpty();
    assertThat(ratingRepository.findByMovie_Id(emptyMovie.getId())).isEmpty();
    assertThat(reviewRepository.findByMovie_Id(emptyMovie.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should cascade delete dependent entities when movie is deleted")
  void shouldCascadeDeleteDependentEntitiesWhenMovieIsDeleted() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());

    var actor =
        personRepository.save(
            Person.builder().name("Cascade Actor").sourceId("cascade-actor-1").build());
    var director =
        personRepository.save(
            Person.builder().name("Cascade Director").sourceId("cascade-dir-1").build());
    var studio =
        companyRepository.save(
            Company.builder().name("Cascade Studio").sourceId("cascade-studio-1").build());
    var genre =
        genreRepository.save(
            Genre.builder().name("Cascade Genre").sourceId("cascade-genre-1").build());

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Cascade Delete Test")
                .library(library)
                .studios(Set.of(studio))
                .cast(List.of(actor))
                .directors(List.of(director))
                .genres(Set.of(genre))
                .build());

    ratingRepository.save(
        Rating.builder().movie(movie).source("CascadeSource").value("9.0").build());
    reviewRepository.save(Review.builder().movie(movie).author("Cascade Author").build());
    mediaFileRepository.save(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .filename("cascade-test.mkv")
            .filepathUri("/test/cascade-test.mkv")
            .status(MediaFileStatus.MATCHED)
            .size(1000L)
            .build());

    var movieId = movie.getId();

    movieRepository.deleteById(movieId);
    movieRepository.flush();

    assertThat(movieRepository.findById(movieId)).isEmpty();
    assertThat(ratingRepository.findByMovie_Id(movieId)).isEmpty();
    assertThat(reviewRepository.findByMovie_Id(movieId)).isEmpty();
    assertThat(companyRepository.findByMovieId(movieId)).isEmpty();
    assertThat(personRepository.findCastByMovieId(movieId)).isEmpty();
    assertThat(personRepository.findDirectorsByMovieId(movieId)).isEmpty();
    assertThat(genreRepository.findByMovieId(movieId)).isEmpty();
    assertThat(mediaFileRepository.findByMediaId(movieId)).isEmpty();
  }

  @Test
  @DisplayName("Should preserve shared entities when movie is deleted")
  void shouldPreserveSharedEntitiesWhenMovieIsDeleted() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());

    var actor =
        personRepository.save(
            Person.builder().name("Surviving Actor").sourceId("surv-actor-1").build());
    var director =
        personRepository.save(
            Person.builder().name("Surviving Director").sourceId("surv-dir-1").build());
    var studio =
        companyRepository.save(
            Company.builder().name("Surviving Studio").sourceId("surv-studio-1").build());
    var genre =
        genreRepository.save(
            Genre.builder().name("Surviving Genre").sourceId("surv-genre-1").build());

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Delete Me Movie")
                .library(library)
                .studios(Set.of(studio))
                .cast(List.of(actor))
                .directors(List.of(director))
                .genres(Set.of(genre))
                .build());

    var studioIds =
        companyRepository.findByMovieId(movie.getId()).stream().map(Company::getId).toList();
    var genreIds = genreRepository.findByMovieId(movie.getId()).stream().map(Genre::getId).toList();

    movieRepository.deleteById(movie.getId());
    movieRepository.flush();

    assertThat(personRepository.findById(actor.getId())).isPresent();
    assertThat(personRepository.findById(director.getId())).isPresent();
    studioIds.forEach(id -> assertThat(companyRepository.findById(id)).isPresent());
    genreIds.forEach(id -> assertThat(genreRepository.findById(id)).isPresent());
  }
}
