package com.streamarr.server.services.metadata.series;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataProvider;
import java.util.Optional;

public interface SeriesMetadataProvider extends MetadataProvider<Series> {

  Optional<SeasonDetails> getSeasonDetails(String seriesExternalId, int seasonNumber);
}
