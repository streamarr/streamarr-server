package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RecordingSeriesMetadataProvider extends RecordingMetadataProvider<Series>
    implements SeriesMetadataProvider {

  private final Map<SeasonKey, SeasonDetails> seasons = new HashMap<>();

  public void willReturnSeasonDetailsFor(String externalId, SeasonDetails details) {
    seasons.put(new SeasonKey(externalId, details.seasonNumber()), details);
  }

  private record SeasonKey(String externalId, int seasonNumber) {}

  @Override
  public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
      UUID libraryId, String seriesExternalId, int seasonNumber) {
    return Optional.ofNullable(seasons.get(new SeasonKey(seriesExternalId, seasonNumber)))
        .<MetadataFetchOutcome<SeasonDetails>>map(MetadataFetchOutcome.Found::new)
        .orElseGet(MetadataFetchOutcome.NotFound::new);
  }
}
