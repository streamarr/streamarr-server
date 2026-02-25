package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataProvider;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public interface SeriesMetadataProvider extends MetadataProvider<Series> {

  Optional<SeasonDetails> getSeasonDetails(
      UUID libraryId, String seriesExternalId, int seasonNumber);

  default OptionalInt resolveSeasonNumber(
      UUID libraryId, String seriesExternalId, int parsedSeasonNumber) {
    return OptionalInt.of(parsedSeasonNumber);
  }

  default List<Integer> getAvailableSeasonNumbers(UUID libraryId, String seriesExternalId) {
    return List.of();
  }
}
