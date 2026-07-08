package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.domain.metadata.Rating;
import com.streamarr.server.domain.metadata.Review;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.RatingRepository;
import com.streamarr.server.repositories.ReviewRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@EnableDgsTest
@DisplayName("Movie Resolver Integration Tests")
class MovieResolverIT extends AbstractIntegrationTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private GenreRepository genreRepository;
  @Autowired private RatingRepository ratingRepository;
  @Autowired private ReviewRepository reviewRepository;
  @Autowired private MediaFileRepository mediaFileRepository;

  @Test
  @DisplayName("Should resolve all movie relationships from movie query")
  @SuppressWarnings("unchecked")
  void shouldResolveAllMovieRelationshipsFromMovieQuery() {
    var movie = createMovieWithRelationships();

    var result =
        dgsQueryExecutor.execute(
            """
            {
              movie(id: "%s") {
                title
                studios { name }
                cast { name }
                directors { name }
                genres { name }
                ratings { source value }
                reviews { author }
                files { filename }
              }
            }
            """
                .formatted(movie.getId()));

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var movieData = (Map<String, Object>) data.get("movie");

    assertThat(movieData).containsEntry("title", "Inception");
    assertThat((List<Map<String, Object>>) movieData.get("studios"))
        .extracting(studio -> studio.get("name"))
        .containsExactly("Warner Bros");
    assertThat((List<Map<String, Object>>) movieData.get("cast"))
        .extracting(castMember -> castMember.get("name"))
        .containsExactly("Leonardo DiCaprio");
    assertThat((List<Map<String, Object>>) movieData.get("directors"))
        .extracting(director -> director.get("name"))
        .containsExactly("Christopher Nolan");
    assertThat((List<Map<String, Object>>) movieData.get("genres"))
        .extracting(genre -> genre.get("name"))
        .containsExactly("Sci-Fi");
    assertThat((List<Map<String, Object>>) movieData.get("ratings"))
        .extracting(rating -> rating.get("source"), rating -> rating.get("value"))
        .containsExactly(tuple("IMDb", "8.8"));
    assertThat((List<Map<String, Object>>) movieData.get("reviews"))
        .extracting(review -> review.get("author"))
        .containsExactly("Roger Ebert");
    assertThat((List<Map<String, Object>>) movieData.get("files")).hasSize(1);
  }

  private Movie createMovieWithRelationships() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var actor =
        personRepository.save(
            Person.builder().name("Leonardo DiCaprio").sourceId(uniqueSourceId()).build());
    var director =
        personRepository.save(
            Person.builder().name("Christopher Nolan").sourceId(uniqueSourceId()).build());
    var studio =
        companyRepository.save(
            Company.builder().name("Warner Bros").sourceId(uniqueSourceId()).build());
    var genre =
        genreRepository.save(Genre.builder().name("Sci-Fi").sourceId(uniqueSourceId()).build());

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Inception")
                .library(library)
                .studios(Set.of(studio))
                .cast(List.of(actor))
                .directors(List.of(director))
                .genres(Set.of(genre))
                .build());

    ratingRepository.save(Rating.builder().movie(movie).source("IMDb").value("8.8").build());
    reviewRepository.save(Review.builder().movie(movie).author("Roger Ebert").build());
    mediaFileRepository.save(
        MediaFile.builder()
            .mediaId(movie.getId())
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("inception.mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build());

    return movie;
  }

  private String uniqueSourceId() {
    return "movie-resolver-it-" + UUID.randomUUID();
  }
}
