package com.streamarr.server.services;

import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;
import lombok.NonNull;

/**
 * A movie's or series' provider credits to save, with the artwork sources for each credited person
 * and studio keyed by source id.
 */
@Builder
public record MediaTitleCreditsRequest(
    @NonNull MediaTitleCredits credits,
    Map<String, List<ImageSource>> personImageSources,
    Map<String, List<ImageSource>> companyImageSources,
    @NonNull ImageRefreshMode imageRefreshMode) {

  public MediaTitleCreditsRequest {
    personImageSources = Objects.requireNonNullElse(personImageSources, Map.of());
    companyImageSources = Objects.requireNonNullElse(companyImageSources, Map.of());
  }

  /** A builder carrying the person and company artwork sources of one metadata result. */
  public static MediaTitleCreditsRequestBuilder withImageSourcesOf(
      @NonNull MetadataResult<?> metadataResult) {
    return builder()
        .personImageSources(metadataResult.personImageSources())
        .companyImageSources(metadataResult.companyImageSources());
  }
}
