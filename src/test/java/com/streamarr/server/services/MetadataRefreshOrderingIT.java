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
  @DisplayName("Should persist refreshed movie cast and directors in billing order")
  void shouldPersistRefreshedMovieCastAndDirectorsInBillingOrder() {
    var sourceSuffix = UUID.randomUUID();
    var firstActor = person("First Billed Actor", "z-first-actor-" + sourceSuffix);
    var secondActor = person("Second Billed Actor", "a-second-actor-" + sourceSuffix);
    var firstDirector = person("First Billed Director", "z-first-director-" + sourceSuffix);
    var secondDirector = person("Second Billed Director", "a-second-director-" + sourceSuffix);
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
  @DisplayName("Should persist refreshed series cast and directors in billing order")
  void shouldPersistRefreshedSeriesCastAndDirectorsInBillingOrder() {
    var sourceSuffix = UUID.randomUUID();
    var firstActor = person("First Billed Series Actor", "z-first-series-actor-" + sourceSuffix);
    var secondActor = person("Second Billed Series Actor", "a-second-series-actor-" + sourceSuffix);
    var firstDirector =
        person("First Billed Series Director", "z-first-series-director-" + sourceSuffix);
    var secondDirector =
        person("Second Billed Series Director", "a-second-series-director-" + sourceSuffix);
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

  private Person person(String name, String sourceId) {
    return personRepository.save(Person.builder().name(name).sourceId(sourceId).build());
  }

  private static Person snapshot(Person person) {
    return Person.builder().name(person.getName()).sourceId(person.getSourceId()).build();
  }
}
