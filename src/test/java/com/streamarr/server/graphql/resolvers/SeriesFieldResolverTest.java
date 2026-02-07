package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
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
@SpringBootTest(classes = {SeriesFieldResolver.class, SeriesResolver.class})
@DisplayName("Series Field Resolver Tests")
class SeriesFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private SeriesRepository seriesRepository;
  @MockitoBean private MediaFileRepository mediaFileRepository;
  @MockitoBean private CompanyRepository companyRepository;
  @MockitoBean private PersonRepository personRepository;
  @MockitoBean private GenreRepository genreRepository;
  @MockitoBean private SeasonRepository seasonRepository;

  private Series setupSeries() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);
    when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
    return series;
  }

  @Test
  @DisplayName("Should return studios when series queried with studios field")
  void shouldReturnStudiosWhenSeriesQueriedWithStudiosField() {
    var series = setupSeries();
    when(companyRepository.findBySeriesId(series.getId()))
        .thenReturn(List.of(Company.builder().name("AMC Studios").sourceId("amc").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { studios { name } } }", series.getId()),
            "data.series.studios[0].name");

    assertThat(name).isEqualTo("AMC Studios");
  }

  @Test
  @DisplayName("Should return cast when series queried with cast field")
  void shouldReturnCastWhenSeriesQueriedWithCastField() {
    var series = setupSeries();
    when(personRepository.findCastBySeriesId(series.getId()))
        .thenReturn(List.of(Person.builder().name("Bryan Cranston").sourceId("bc").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { cast { name } } }", series.getId()),
            "data.series.cast[0].name");

    assertThat(name).isEqualTo("Bryan Cranston");
  }

  @Test
  @DisplayName("Should return directors when series queried with directors field")
  void shouldReturnDirectorsWhenSeriesQueriedWithDirectorsField() {
    var series = setupSeries();
    when(personRepository.findDirectorsBySeriesId(series.getId()))
        .thenReturn(List.of(Person.builder().name("Vince Gilligan").sourceId("vg").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { directors { name } } }", series.getId()),
            "data.series.directors[0].name");

    assertThat(name).isEqualTo("Vince Gilligan");
  }

  @Test
  @DisplayName("Should return genres when series queried with genres field")
  void shouldReturnGenresWhenSeriesQueriedWithGenresField() {
    var series = setupSeries();
    when(genreRepository.findBySeriesId(series.getId()))
        .thenReturn(List.of(Genre.builder().name("Drama").sourceId("drama").build()));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { genres { name } } }", series.getId()),
            "data.series.genres[0].name");

    assertThat(name).isEqualTo("Drama");
  }

  @Test
  @DisplayName("Should return seasons when series queried with seasons field")
  void shouldReturnSeasonsWhenSeriesQueriedWithSeasonsField() {
    var series = setupSeries();
    var season = Season.builder().title("Season 1").seasonNumber(1).build();
    season.setId(UUID.randomUUID());

    when(seasonRepository.findBySeriesId(series.getId())).thenReturn(List.of(season));

    Integer seasonNumber =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ series(id: \"%s\") { seasons { title seasonNumber } } }", series.getId()),
            "data.series.seasons[0].seasonNumber");

    assertThat(seasonNumber).isEqualTo(1);
  }
}
