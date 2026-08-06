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

  private Person savePerson(Person person) {
    return personRepository.save(person);
  }

  private static Person snapshot(Person person) {
    return Person.builder().name(person.getName()).sourceId(person.getSourceId()).build();
  }
}
