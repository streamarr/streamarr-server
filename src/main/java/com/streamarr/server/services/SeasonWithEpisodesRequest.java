package com.streamarr.server.services;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import lombok.Builder;
import lombok.NonNull;

/** Provider details for one season of a series, with the run that fetches its artwork. */
@Builder
public record SeasonWithEpisodesRequest(
    @NonNull Series series,
    @NonNull SeasonDetails details,
    @NonNull Library library,
    @NonNull ArtworkRun artworkRun) {}
