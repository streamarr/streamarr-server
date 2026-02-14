package com.streamarr.server.services.metadata.series;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.library.events.ScanEndedEvent;
import com.streamarr.server.services.metadata.TmdbSearchDelegate;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSearchResult;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSearchResults;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeason;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeasonSummary;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("TMDB Series Provider Tests")
class TMDBSeriesProviderTest {

  private FakeTmdbHttpService fakeTmdbHttpService;
  private TMDBSeriesProvider provider;

  @BeforeEach
  void setUp() {
    fakeTmdbHttpService = new FakeTmdbHttpService();
    var searchDelegate = new TmdbSearchDelegate(fakeTmdbHttpService);
    provider = new TMDBSeriesProvider(fakeTmdbHttpService, searchDelegate);
  }

  @Test
  @DisplayName("Should resolve season number when series metadata available")
  void shouldResolveSeasonNumberWhenSeriesMetadataAvailable() throws IOException {
    var series =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(1).airDate("2020-01-15").build()))
            .build();

    fakeTmdbHttpService.setTvSeriesMetadata("1396", series);

    var result = provider.resolveSeasonNumber("1396", 2020);

    assertThat(result).isEqualTo(OptionalInt.of(1));
  }

  @Test
  @DisplayName("Should return fresh data when cache cleared by scan ended")
  void shouldReturnFreshDataWhenCacheClearedByScanEnded() throws IOException {
    var initialSeries =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(1).airDate("2020-01-15").build()))
            .build();

    fakeTmdbHttpService.setTvSeriesMetadata("1396", initialSeries);
    provider.resolveSeasonNumber("1396", 2020);

    var updatedSeries =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(3).airDate("2020-03-01").build()))
            .build();

    fakeTmdbHttpService.setTvSeriesMetadata("1396", updatedSeries);
    provider.onScanEnded(new ScanEndedEvent(UUID.randomUUID()));

    var result = provider.resolveSeasonNumber("1396", 2020);
    assertThat(result).isEqualTo(OptionalInt.of(3));
  }

  @Test
  @DisplayName("Should return empty from cache when season details previously failed")
  void shouldReturnEmptyFromCacheWhenSeasonDetailsPreviouslyFailed() throws IOException {
    var validSeason =
        TmdbTvSeason.builder()
            .name("Season 5")
            .seasonNumber(5)
            .episodes(Collections.emptyList())
            .build();

    fakeTmdbHttpService.setSeasonDetailsFailOnFirstCall("1396", 5);
    fakeTmdbHttpService.setTvSeasonDetails("1396", 5, validSeason);

    var firstResult = provider.getSeasonDetails("1396", 5);
    assertThat(firstResult).isEmpty();

    var secondResult = provider.getSeasonDetails("1396", 5);
    assertThat(secondResult)
        .as("Second call should return empty from negative cache despite TMDB now having data")
        .isEmpty();
  }

  @Test
  @DisplayName("Should find series when year-filtered search returns no results")
  void shouldFindSeriesWhenYearFilteredSearchReturnsNoResults() {
    var videoInfo = VideoFileParserResult.builder().title("Patriot").year("2017").build();

    var patriotResult =
        TmdbTvSearchResult.builder()
            .id(56484)
            .name("Patriot")
            .originalName("Patriot")
            .firstAirDate("2015-11-05")
            .popularity(25.0)
            .build();

    fakeTmdbHttpService.setTvSearchResponse(
        "2017", TmdbTvSearchResults.builder().results(Collections.emptyList()).build());
    fakeTmdbHttpService.setTvSearchResponse(
        null, TmdbTvSearchResults.builder().results(List.of(patriotResult)).build());

    var result = provider.search(videoInfo);

    assertThat(result).isPresent();
    assertThat(result.get().externalId()).isEqualTo("56484");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }

  @Test
  @DisplayName("Should clear negative season details cache when scan ends")
  void shouldClearNegativeSeasonDetailsCacheWhenScanEnds() throws IOException {
    var validSeason =
        TmdbTvSeason.builder()
            .name("Season 5")
            .seasonNumber(5)
            .episodes(Collections.emptyList())
            .build();

    fakeTmdbHttpService.setSeasonDetailsFailOnFirstCall("1396", 5);
    fakeTmdbHttpService.setTvSeasonDetails("1396", 5, validSeason);

    var firstResult = provider.getSeasonDetails("1396", 5);
    assertThat(firstResult).isEmpty();

    provider.onScanEnded(new ScanEndedEvent(UUID.randomUUID()));

    var afterClearResult = provider.getSeasonDetails("1396", 5);
    assertThat(afterClearResult)
        .as("After cache clear, should fetch fresh data from TMDB")
        .isPresent();
  }
}
