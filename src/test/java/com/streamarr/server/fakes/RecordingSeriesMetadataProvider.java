package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import java.util.Optional;
import java.util.UUID;

public class RecordingSeriesMetadataProvider extends RecordingMetadataProvider<Series>
    implements SeriesMetadataProvider {

  @Override
  public Optional<SeasonDetails> getSeasonDetails(
      UUID libraryId, String seriesExternalId, int seasonNumber) {
    return Optional.empty();
  }
}
