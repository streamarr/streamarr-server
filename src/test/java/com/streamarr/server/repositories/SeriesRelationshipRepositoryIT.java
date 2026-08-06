package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Series Relationship Repository Integration Tests")
class SeriesRelationshipRepositoryIT extends AbstractIntegrationTest {

  @Autowired private SeriesRepository seriesRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private GenreRepository genreRepository;
  @Autowired private PersonRepository personRepository;

  @Test
  @DisplayName("Should find studios by series ID")
  void shouldFindStudiosBySeriesId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var studio =
        companyRepository.save(
            Company.builder().name("HBO Entertainment").sourceId("hbo-1").build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("The Sopranos")
                .library(library)
                .studios(Set.of(studio))
                .build());

    var studios = companyRepository.findBySeriesId(series.getId());

    assertThat(studios).singleElement().extracting(Company::getName).isEqualTo("HBO Entertainment");
  }

  @Test
  @DisplayName("Should find genres by series ID")
  void shouldFindGenresBySeriesId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var genre =
        genreRepository.save(Genre.builder().name("Crime").sourceId("series-crime-1").build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Crime Series").library(library).genres(Set.of(genre)).build());

    var genres = genreRepository.findBySeriesId(series.getId());

    assertThat(genres).singleElement().extracting(Genre::getName).isEqualTo("Crime");
  }

  @Test
  @DisplayName("Should find cast by series ID")
  void shouldFindCastBySeriesId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var actor =
        personRepository.save(
            Person.builder().name("Series Actor").sourceId("series-actor-1").build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder().title("Cast Series").library(library).cast(List.of(actor)).build());

    var cast = personRepository.findCastBySeriesId(series.getId());

    assertThat(cast).singleElement().extracting(Person::getName).isEqualTo("Series Actor");
  }

  @Test
  @DisplayName("Should find directors by series ID")
  void shouldFindDirectorsBySeriesId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var director =
        personRepository.save(
            Person.builder().name("Series Director").sourceId("series-director-1").build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Director Series")
                .library(library)
                .directors(List.of(director))
                .build());

    var directors = personRepository.findDirectorsBySeriesId(series.getId());

    assertThat(directors).singleElement().extracting(Person::getName).isEqualTo("Series Director");
  }
}
