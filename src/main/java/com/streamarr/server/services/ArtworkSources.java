package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.services.ArtworkResult.Failed;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ArtworkSources(
    @NonNull UUID entityId,
    @NonNull ImageEntityType entityType,
    @NonNull List<ImageSource> sources) {

  /** Image types the entity needs but the provider supplied no source for. */
  public List<ImageType> missingImageTypes() {
    var suppliedTypes = sources.stream().map(ImageSource::imageType).collect(Collectors.toSet());
    return expectedImageTypes().stream().filter(type -> !suppliedTypes.contains(type)).toList();
  }

  /** One image type per requested source image: each source plus each missing image type. */
  public List<ImageType> requestedImageTypes() {
    return Stream.concat(sources.stream().map(ImageSource::imageType), missingImageTypes().stream())
        .toList();
  }

  List<ArtworkResult> failures(Throwable cause) {
    return requestedImageTypes().stream()
        .<ArtworkResult>map(imageType -> new Failed(imageType, cause))
        .toList();
  }

  private Set<ImageType> expectedImageTypes() {
    return switch (entityType) {
      case MOVIE, SERIES -> EnumSet.of(ImageType.POSTER, ImageType.BACKDROP);
      case SEASON -> EnumSet.of(ImageType.POSTER);
      case EPISODE -> EnumSet.of(ImageType.STILL);
      case PERSON -> EnumSet.of(ImageType.PROFILE);
      case COMPANY -> EnumSet.of(ImageType.LOGO);
    };
  }
}
