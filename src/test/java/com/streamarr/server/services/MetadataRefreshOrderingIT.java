package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.metadata.MetadataResult;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Metadata Refresh Ordering Integration Tests")
class MetadataRefreshOrderingIT extends AbstractIntegrationTest {

  @Autowired private MovieService movieService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesService seriesService;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName(
      "Should persist movie cast and directors in billing order when metadata is refreshed")
  void shouldPersistMovieCastAndDirectorsInBillingOrderWhenMetadataIsRefreshed() {
    var sourceSuffix = UUID.randomUUID();
    var firstActor =
        savePerson(
            Person.builder()
                .name("First Billed Actor")
                .sourceId("z-first-actor-" + sourceSuffix)
                .build());
    var secondActor =
        savePerson(
            Person.builder()
                .name("Second Billed Actor")
                .sourceId("a-second-actor-" + sourceSuffix)
                .build());
    var firstDirector =
        savePerson(
            Person.builder()
                .name("First Billed Director")
                .sourceId("z-first-director-" + sourceSuffix)
                .build());
    var secondDirector =
        savePerson(
            Person.builder()
                .name("Second Billed Director")
                .sourceId("a-second-director-" + sourceSuffix)
                .build());
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Refresh Ordering Movie")
                .library(library)
                .cast(List.of(secondActor, firstActor))
                .directors(List.of(secondDirector, firstDirector))
                .build());
    var fresh =
        Movie.builder()
            .title(movie.getTitle())
            .cast(List.of(snapshot(firstActor), snapshot(secondActor)))
            .directors(List.of(snapshot(firstDirector), snapshot(secondDirector)))
            .build();

    movieService.refreshMovieMetadata(
        movie, new MetadataResult<>(fresh, List.of(), Map.of(), Map.of()));
    entityManager.clear();

    assertThat(movieService.findCast(movie.getId()))
        .extracting(Person::getName)
        .containsExactly("First Billed Actor", "Second Billed Actor");
    assertThat(movieService.findDirectors(movie.getId()))
        .extracting(Person::getName)
        .containsExactly("First Billed Director", "Second Billed Director");
  }

  @Test
  @DisplayName(
      "Should persist series cast and directors in billing order when metadata is refreshed")
  void shouldPersistSeriesCastAndDirectorsInBillingOrderWhenMetadataIsRefreshed() {
    var sourceSuffix = UUID.randomUUID();
    var firstActor =
        savePerson(
            Person.builder()
                .name("First Billed Series Actor")
                .sourceId("z-first-series-actor-" + sourceSuffix)
                .build());
    var secondActor =
        savePerson(
            Person.builder()
                .name("Second Billed Series Actor")
                .sourceId("a-second-series-actor-" + sourceSuffix)
                .build());
    var firstDirector =
        savePerson(
            Person.builder()
                .name("First Billed Series Director")
                .sourceId("z-first-series-director-" + sourceSuffix)
                .build());
    var secondDirector =
        savePerson(
            Person.builder()
                .name("Second Billed Series Director")
                .sourceId("a-second-series-director-" + sourceSuffix)
                .build());
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Refresh Ordering Series")
                .library(library)
                .cast(List.of(secondActor, firstActor))
                .directors(List.of(secondDirector, firstDirector))
                .build());
    var fresh =
        Series.builder()
            .title(series.getTitle())
            .cast(List.of(snapshot(firstActor), snapshot(secondActor)))
            .directors(List.of(snapshot(firstDirector), snapshot(secondDirector)))
            .build();

    seriesService.refreshSeriesMetadata(
        series, new MetadataResult<>(fresh, List.of(), Map.of(), Map.of()));
    entityManager.clear();

    assertThat(seriesService.findCast(series.getId()))
        .extracting(Person::getName)
        .containsExactly("First Billed Series Actor", "Second Billed Series Actor");
    assertThat(seriesService.findDirectors(series.getId()))
        .extracting(Person::getName)
        .containsExactly("First Billed Series Director", "Second Billed Series Director");
  }

  @Test
  @DisplayName("Should remove movie people when refreshed metadata has no cast or directors")
  void shouldRemoveMoviePeopleWhenRefreshedMetadataHasNoCastOrDirectors() {
    var sourceSuffix = UUID.randomUUID();
    var actor =
        savePerson(
            Person.builder()
                .name("Removed Movie Actor")
                .sourceId("removed-movie-actor-" + sourceSuffix)
                .build());
    var director =
        savePerson(
            Person.builder()
                .name("Removed Movie Director")
                .sourceId("removed-movie-director-" + sourceSuffix)
                .build());
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Refresh Empty Movie People")
                .library(library)
                .cast(List.of(actor))
                .directors(List.of(director))
                .build());
    var fresh =
        Movie.builder().title(movie.getTitle()).cast(List.of()).directors(List.of()).build();

    movieService.refreshMovieMetadata(
        movie, new MetadataResult<>(fresh, List.of(), Map.of(), Map.of()));
    entityManager.clear();

    assertThat(movieService.findCast(movie.getId())).isEmpty();
    assertThat(movieService.findDirectors(movie.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should remove series people when refreshed metadata has no cast or directors")
  void shouldRemoveSeriesPeopleWhenRefreshedMetadataHasNoCastOrDirectors() {
    var sourceSuffix = UUID.randomUUID();
    var actor =
        savePerson(
            Person.builder()
                .name("Removed Series Actor")
                .sourceId("removed-series-actor-" + sourceSuffix)
                .build());
    var director =
        savePerson(
            Person.builder()
                .name("Removed Series Director")
                .sourceId("removed-series-director-" + sourceSuffix)
                .build());
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Refresh Empty Series People")
                .library(library)
                .cast(List.of(actor))
                .directors(List.of(director))
                .build());
    var fresh =
        Series.builder().title(series.getTitle()).cast(List.of()).directors(List.of()).build();

    seriesService.refreshSeriesMetadata(
        series, new MetadataResult<>(fresh, List.of(), Map.of(), Map.of()));
    entityManager.clear();

    assertThat(seriesService.findCast(series.getId())).isEmpty();
    assertThat(seriesService.findDirectors(series.getId())).isEmpty();
  }

  private Person savePerson(Person person) {
    return personRepository.save(person);
  }

  private static Person snapshot(Person person) {
    return Person.builder().name(person.getName()).sourceId(person.getSourceId()).build();
  }
}
