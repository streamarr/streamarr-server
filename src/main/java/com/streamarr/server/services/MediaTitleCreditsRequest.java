package com.streamarr.server.services;

import com.streamarr.server.domain.media.CreditedMediaTitle;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Builder;
import lombok.NonNull;

/**
 * A media title's provider credits to save, with the artwork sources for each credited person and
 * studio keyed by source id.
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

  /** The credits listed on a metadata result's media title, with the result's artwork sources. */
  public static MediaTitleCreditsRequest fromMetadataResult(
      @NonNull MetadataResult<? extends CreditedMediaTitle> metadataResult,
      @NonNull ImageRefreshMode imageRefreshMode) {
    return builder()
        .credits(MediaTitleCredits.listedOn(metadataResult.entity()))
        .personImageSources(metadataResult.personImageSources())
        .companyImageSources(metadataResult.companyImageSources())
        .imageRefreshMode(imageRefreshMode)
        .build();
  }
}
