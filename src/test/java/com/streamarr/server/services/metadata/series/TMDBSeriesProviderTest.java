package com.streamarr.server.services.metadata.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.services.library.events.ScanEndedEvent;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbSearchDelegate;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeasonSummary;
import com.streamarr.server.services.metadata.tmdb.TmdbTvSeries;
import java.io.IOException;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("TMDB Series Provider Tests")
class TMDBSeriesProviderTest {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService =
      mock(TheMovieDatabaseHttpService.class);

  private final TmdbSearchDelegate searchDelegate =
      new TmdbSearchDelegate(theMovieDatabaseHttpService);

  private final TMDBSeriesProvider provider =
      new TMDBSeriesProvider(theMovieDatabaseHttpService, searchDelegate);

  @Test
  @DisplayName("Should resolve season number when series metadata available")
  void shouldResolveSeasonNumberWhenSeriesMetadataAvailable()
      throws IOException, InterruptedException {
    var seriesExternalId = "1396";

    var series =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(1).airDate("2020-01-15").build()))
            .build();

    when(theMovieDatabaseHttpService.getTvSeriesMetadata(anyString())).thenReturn(series);

    var result = provider.resolveSeasonNumber(seriesExternalId, 2020);

    assertThat(result).isEqualTo(OptionalInt.of(1));
  }

  @Test
  @DisplayName("Should return fresh data when cache cleared by scan ended")
  void shouldReturnFreshDataWhenCacheClearedByScanEnded() throws IOException, InterruptedException {
    var seriesExternalId = "1396";

    var initialSeries =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(1).airDate("2020-01-15").build()))
            .build();

    when(theMovieDatabaseHttpService.getTvSeriesMetadata(anyString())).thenReturn(initialSeries);

    provider.resolveSeasonNumber(seriesExternalId, 2020);

    var updatedSeries =
        TmdbTvSeries.builder()
            .seasons(
                List.of(
                    TmdbTvSeasonSummary.builder().seasonNumber(3).airDate("2020-03-01").build()))
            .build();

    when(theMovieDatabaseHttpService.getTvSeriesMetadata(anyString())).thenReturn(updatedSeries);

    provider.onScanEnded(new ScanEndedEvent(UUID.randomUUID()));

    var result = provider.resolveSeasonNumber(seriesExternalId, 2020);
    assertThat(result).isEqualTo(OptionalInt.of(3));
  }
}
