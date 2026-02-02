package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("IntegrationTest")
@DisplayName("Movie Repository Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MovieRepositoryIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryRepository libraryRepository;

  private Library savedLibrary;

  @BeforeAll
  public void setup() {
    var fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();

    savedLibrary = libraryRepository.save(fakeLibrary);
  }

  @Test
  @DisplayName("Should save a Movie with its MediaFile when no existing Movie in the database.")
  void shouldSaveMovieWithMediaFile() {
    var file =
        MediaFile.builder()
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("a-wonderful-test-[1080p].mkv")
            .filepath("/root/a-wonderful-test-[1080p].mkv")
            .build();

    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("A Wonderful Test")
                .files(Set.of(file))
                .library(savedLibrary)
                .build());

    assertThat(movie.getFiles()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should save a Movie with its external identifier when no existing Movie in the database.")
  @Transactional
  void shouldSaveMovieWithExternalIdentifier() {
    var fakeExternalId =
        ExternalIdentifier.builder()
            .externalId("123")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("A Wonderful Test")
            .externalIds(Set.of(fakeExternalId))
            .library(savedLibrary)
            .build());

    var result = movieRepository.findByTmdbId(fakeExternalId.getExternalId());

    assertThat(result).isPresent();
    assertThat(result.get().getExternalIds()).hasSize(1);
  }

  @Test
  @DisplayName("Should only match TMDB source when another source shares the same external ID.")
  @Transactional
  void shouldOnlyMatchTmdbSourceWhenAnotherSourceSharesSameExternalId() {
    var sharedId = "99999";

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("IMDB Movie")
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalId(sharedId)
                        .externalSourceType(ExternalSourceType.IMDB)
                        .build()))
            .library(savedLibrary)
            .build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("TMDB Movie")
            .externalIds(
                Set.of(
                    ExternalIdentifier.builder()
                        .externalId(sharedId)
                        .externalSourceType(ExternalSourceType.TMDB)
                        .build()))
            .library(savedLibrary)
            .build());

    var result = movieRepository.findByTmdbId(sharedId);

    assertThat(result).isPresent();
    assertThat(result.get().getTitle()).isEqualTo("TMDB Movie");
  }
}
