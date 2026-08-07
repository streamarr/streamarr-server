package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.SeriesDirector.SERIES_DIRECTOR;
import static com.streamarr.server.jooq.generated.tables.SeriesPerson.SERIES_PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
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
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("Should return empty relationships when series has no associated metadata")
  void shouldReturnEmptyRelationshipsWhenSeriesHasNoAssociatedMetadata() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var studio =
        companyRepository.save(
            Company.builder().name("Control Studio").sourceId("control-studio-1").build());
    var genre =
        genreRepository.save(
            Genre.builder().name("Control Genre").sourceId("control-genre-1").build());
    var actor =
        personRepository.save(
            Person.builder().name("Control Actor").sourceId("control-actor-1").build());
    var director =
        personRepository.save(
            Person.builder().name("Control Director").sourceId("control-director-1").build());
    seriesRepository.saveAndFlush(
        Series.builder()
            .title("Populated Control Series")
            .library(library)
            .studios(Set.of(studio))
            .genres(Set.of(genre))
            .cast(List.of(actor))
            .directors(List.of(director))
            .build());
    var emptySeries =
        seriesRepository.saveAndFlush(
            Series.builder().title("Empty Target Series").library(library).build());

    assertThat(companyRepository.findBySeriesId(emptySeries.getId())).isEmpty();
    assertThat(genreRepository.findBySeriesId(emptySeries.getId())).isEmpty();
    assertThat(personRepository.findCastBySeriesId(emptySeries.getId())).isEmpty();
    assertThat(personRepository.findDirectorsBySeriesId(emptySeries.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should find studios when series ID has associated studios")
  void shouldFindStudiosWhenSeriesIdHasAssociatedStudios() {
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
  @DisplayName("Should find genres when series ID has associated genres")
  void shouldFindGenresWhenSeriesIdHasAssociatedGenres() {
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
  @DisplayName("Should find cast when series ID has associated cast")
  void shouldFindCastWhenSeriesIdHasAssociatedCast() {
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
  @DisplayName("Should find directors when series ID has associated directors")
  void shouldFindDirectorsWhenSeriesIdHasAssociatedDirectors() {
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

  @Test
  @DisplayName("Should find series cast by ordinal then person ID")
  void shouldFindSeriesCastByOrdinalThenPersonId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var tiedActorA =
        personRepository.save(
            Person.builder().name("Tied Series Actor A").sourceId("tied-series-actor-a-1").build());
    var tiedActorB =
        personRepository.save(
            Person.builder().name("Tied Series Actor B").sourceId("tied-series-actor-b-1").build());
    var laterActor =
        personRepository.save(
            Person.builder().name("Later Series Actor").sourceId("later-series-actor-1").build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Ordered Cast Series")
                .library(library)
                .cast(List.of(tiedActorA, laterActor, tiedActorB))
                .build());
    dsl.update(SERIES_PERSON)
        .set(SERIES_PERSON.ORDINAL, 0)
        .where(
            SERIES_PERSON
                .SERIES_ID
                .eq(series.getId())
                .and(SERIES_PERSON.PERSON_ID.in(tiedActorA.getId(), tiedActorB.getId())))
        .execute();
    dsl.update(SERIES_PERSON)
        .set(SERIES_PERSON.ORDINAL, 1)
        .where(
            SERIES_PERSON
                .SERIES_ID
                .eq(series.getId())
                .and(SERIES_PERSON.PERSON_ID.eq(laterActor.getId())))
        .execute();

    var cast = personRepository.findCastBySeriesId(series.getId());
    var expectedIds =
        Stream.concat(
                idsInPostgresOrder(tiedActorA, tiedActorB).stream(), Stream.of(laterActor.getId()))
            .toList();

    assertThat(cast).extracting(Person::getId).containsExactlyElementsOf(expectedIds);
  }

  @Test
  @DisplayName("Should find series directors by ordinal then person ID")
  void shouldFindSeriesDirectorsByOrdinalThenPersonId() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var tiedDirectorA =
        personRepository.save(
            Person.builder()
                .name("Tied Series Director A")
                .sourceId("tied-series-director-a-1")
                .build());
    var tiedDirectorB =
        personRepository.save(
            Person.builder()
                .name("Tied Series Director B")
                .sourceId("tied-series-director-b-1")
                .build());
    var laterDirector =
        personRepository.save(
            Person.builder()
                .name("Later Series Director")
                .sourceId("later-series-director-1")
                .build());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Ordered Directors Series")
                .library(library)
                .directors(List.of(tiedDirectorA, laterDirector, tiedDirectorB))
                .build());
    dsl.update(SERIES_DIRECTOR)
        .set(SERIES_DIRECTOR.ORDINAL, 0)
        .where(
            SERIES_DIRECTOR
                .SERIES_ID
                .eq(series.getId())
                .and(SERIES_DIRECTOR.PERSON_ID.in(tiedDirectorA.getId(), tiedDirectorB.getId())))
        .execute();
    dsl.update(SERIES_DIRECTOR)
        .set(SERIES_DIRECTOR.ORDINAL, 1)
        .where(
            SERIES_DIRECTOR
                .SERIES_ID
                .eq(series.getId())
                .and(SERIES_DIRECTOR.PERSON_ID.eq(laterDirector.getId())))
        .execute();

    var directors = personRepository.findDirectorsBySeriesId(series.getId());
    var expectedIds =
        Stream.concat(
                idsInPostgresOrder(tiedDirectorA, tiedDirectorB).stream(),
                Stream.of(laterDirector.getId()))
            .toList();

    assertThat(directors).extracting(Person::getId).containsExactlyElementsOf(expectedIds);
  }

  private static List<UUID> idsInPostgresOrder(Person... persons) {
    return Stream.of(persons)
        .map(Person::getId)
        .sorted(Comparator.comparing(UUID::toString))
        .toList();
  }
}
