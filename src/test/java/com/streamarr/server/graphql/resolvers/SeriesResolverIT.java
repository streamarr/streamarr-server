package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.security.WithProfileContext;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.time.LocalDate;
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
@DisplayName("Series Resolver Integration Tests")
@WithProfileContext
class SeriesResolverIT extends AbstractIntegrationTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private PersonRepository personRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private GenreRepository genreRepository;

  @Test
  @DisplayName("Should resolve series scalar fields from season query")
  @SuppressWarnings("unchecked")
  void shouldResolveSeriesScalarFieldsFromSeasonQuery() {
    var season = createSeason();

    var result =
        dgsQueryExecutor.execute(
            """
            {
              season(id: "%s") {
                series {
                  title
                  firstAirDate
                }
              }
            }
            """
                .formatted(season.getId()));

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var seasonData = (Map<String, Object>) data.get("season");
    var seriesData = (Map<String, Object>) seasonData.get("series");

    assertThat(seriesData)
        .containsEntry("title", "The Vampire Diaries")
        .containsEntry("firstAirDate", "2009-09-10");
  }

  @Test
  @DisplayName("Should return seasons ordered by season number from series query")
  @SuppressWarnings("unchecked")
  void shouldReturnSeasonsOrderedBySeasonNumberFromSeriesQuery() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    seasonRepository.saveAllAndFlush(
        List.of(
            createSeason(library, series, 2),
            createSeason(library, series, 3),
            createSeason(library, series, 4),
            createSeason(library, series, 0),
            createSeason(library, series, 1),
            createSeason(library, series, 5)));

    var result =
        dgsQueryExecutor.execute(
            """
            {
              series(id: "%s") {
                seasons {
                  seasonNumber
                }
              }
            }
            """
                .formatted(series.getId()));

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var seriesData = (Map<String, Object>) data.get("series");
    var seasons = (List<Map<String, Object>>) seriesData.get("seasons");

    assertThat(seasons)
        .extracting(season -> season.get("seasonNumber"))
        .containsExactly(0, 1, 2, 3, 4, 5);
  }

  @Test
  @DisplayName("Should return episodes ordered by episode number from season query")
  @SuppressWarnings("unchecked")
  void shouldReturnEpisodesOrderedByEpisodeNumberFromSeasonQuery() {
    var season = createSeason();
    var library = season.getLibrary();
    episodeRepository.saveAllAndFlush(
        List.of(
            createEpisode(library, season, 1),
            createEpisode(library, season, 17),
            createEpisode(library, season, 2)));

    var result =
        dgsQueryExecutor.execute(
            """
            {
              season(id: "%s") {
                episodes {
                  episodeNumber
                }
              }
            }
            """
                .formatted(season.getId()));

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var seasonData = (Map<String, Object>) data.get("season");
    var episodes = (List<Map<String, Object>>) seasonData.get("episodes");

    assertThat(episodes)
        .extracting(episode -> episode.get("episodeNumber"))
        .containsExactly(1, 2, 17);
  }

  @Test
  @DisplayName("Should resolve studios cast directors and genres from series query")
  @SuppressWarnings("unchecked")
  void shouldResolveStudiosCastDirectorsAndGenresFromSeriesQuery() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var actor =
        personRepository.save(
            Person.builder().name("Nina Dobrev").sourceId(uniqueSourceId()).build());
    var director =
        personRepository.save(
            Person.builder().name("Kevin Williamson").sourceId(uniqueSourceId()).build());
    var studio =
        companyRepository.save(
            Company.builder().name("Warner Bros").sourceId(uniqueSourceId()).build());
    var genre =
        genreRepository.save(Genre.builder().name("Fantasy").sourceId(uniqueSourceId()).build());

    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("The Vampire Diaries")
                .titleSort("Vampire Diaries, The")
                .library(library)
                .studios(Set.of(studio))
                .cast(List.of(actor))
                .directors(List.of(director))
                .genres(Set.of(genre))
                .build());

    var result =
        dgsQueryExecutor.execute(
            """
            {
              series(id: "%s") {
                studios { name }
                cast { name }
                directors { name }
                genres { name }
              }
            }
            """
                .formatted(series.getId()));

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var seriesData = (Map<String, Object>) data.get("series");

    assertThat((List<Map<String, Object>>) seriesData.get("studios"))
        .extracting(studioData -> studioData.get("name"))
        .containsExactly("Warner Bros");
    assertThat((List<Map<String, Object>>) seriesData.get("cast"))
        .extracting(castData -> castData.get("name"))
        .containsExactly("Nina Dobrev");
    assertThat((List<Map<String, Object>>) seriesData.get("directors"))
        .extracting(directorData -> directorData.get("name"))
        .containsExactly("Kevin Williamson");
    assertThat((List<Map<String, Object>>) seriesData.get("genres"))
        .extracting(genreData -> genreData.get("name"))
        .containsExactly("Fantasy");
  }

  private String uniqueSourceId() {
    return "series-resolver-it-" + UUID.randomUUID();
  }

  private Season createSeason() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    return seasonRepository.saveAndFlush(createSeason(library, series, 1));
  }

  private Season createSeason(Library library, Series series, int seasonNumber) {
    return Season.builder().seasonNumber(seasonNumber).series(series).library(library).build();
  }

  private Series createSeries(Library library) {
    return seriesRepository.saveAndFlush(
        Series.builder()
            .title("The Vampire Diaries")
            .titleSort("Vampire Diaries, The")
            .firstAirDate(LocalDate.parse("2009-09-10"))
            .library(library)
            .build());
  }

  private Episode createEpisode(Library library, Season season, int episodeNumber) {
    return Episode.builder()
        .episodeNumber(episodeNumber)
        .title("Episode %d".formatted(episodeNumber))
        .library(library)
        .season(season)
        .build();
  }
}
