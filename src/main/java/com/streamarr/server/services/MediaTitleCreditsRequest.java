package com.streamarr.server.services;

import com.streamarr.server.domain.media.CreditedMediaTitle;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A media title's provider credits to save, with the artwork sources for each credited person and
 * studio keyed by source id.
 *
 * @param libraryOperationId the library operation that saves the credits, if any
 */
@Builder(toBuilder = true)
public record MediaTitleCreditsRequest(
    @NonNull MediaTitleCredits credits,
    Map<String, List<ImageSource>> personImageSources,
    Map<String, List<ImageSource>> companyImageSources,
    @NonNull ImageRefreshMode imageRefreshMode,
    Optional<UUID> libraryOperationId) {

  public MediaTitleCreditsRequest {
    personImageSources = Objects.requireNonNullElse(personImageSources, Map.of());
    companyImageSources = Objects.requireNonNullElse(companyImageSources, Map.of());
    libraryOperationId = Objects.requireNonNullElse(libraryOperationId, Optional.empty());
  }

  /**
   * The credits listed on a metadata result's media title, with the result's artwork sources, saved
   * for the artwork run's library operation, if any.
   */
  public static MediaTitleCreditsRequest fromMetadataResult(
      @NonNull MetadataResult<? extends CreditedMediaTitle> metadataResult,
      @NonNull ImageRefreshMode imageRefreshMode,
      @NonNull ArtworkRun artworkRun) {
    return builder()
        .credits(MediaTitleCredits.listedOn(metadataResult.entity()))
        .personImageSources(metadataResult.personImageSources())
        .companyImageSources(metadataResult.companyImageSources())
        .imageRefreshMode(imageRefreshMode)
        .libraryOperationId(artworkRun.libraryOperationId())
        .build();
  }

  /** The same credits without artwork sources, so saving them requests no artwork. */
  MediaTitleCreditsRequest withoutArtwork() {
    return toBuilder().personImageSources(Map.of()).companyImageSources(Map.of()).build();
  }
}
