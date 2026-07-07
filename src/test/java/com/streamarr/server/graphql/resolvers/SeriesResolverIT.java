package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@EnableDgsTest
@DisplayName("Series Resolver Integration Tests")
class SeriesResolverIT extends AbstractIntegrationTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;

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

    assertThat(seriesData.get("title")).isEqualTo("The Vampire Diaries");
    assertThat(seriesData.get("firstAirDate")).isEqualTo("2009-09-10");
  }

  @Test
  @DisplayName("Should resolve seasons by season number from series query")
  @SuppressWarnings("unchecked")
  void shouldResolveSeasonsBySeasonNumberFromSeriesQuery() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    seasonRepository.saveAllAndFlush(
        List.of(
            createSeason(library, series, 2),
            createSeason(library, series, 3),
            createSeason(library, series, 4),
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
        .containsExactly(1, 2, 3, 4, 5);
  }

  @Test
  @DisplayName("Should resolve episodes by episode number from season query")
  @SuppressWarnings("unchecked")
  void shouldResolveEpisodesByEpisodeNumberFromSeasonQuery() {
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
