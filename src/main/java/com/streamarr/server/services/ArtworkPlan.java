package com.streamarr.server.services;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/**
 * What one artwork attempt does with each image type that an item needs, given the images the item
 * already stores and the refresh mode. A missing image is always downloaded; the refresh mode
 * decides whether a stored image is kept or replaced.
 */
public record ArtworkPlan(@NonNull List<PlannedImage> images) {

  public ArtworkPlan {
    images = List.copyOf(images);
  }

  /** What an attempt does with one image type. */
  public sealed interface PlannedImage {

    ImageType imageType();

    /** The stored image stays, because the refresh mode does not require the provider's image. */
    record KeepStoredImage(ImageType imageType) implements PlannedImage {}

    /** The provider's image is downloaded; it replaces the stored image when there is one. */
    record DownloadImage(ImageSource source, boolean replacesStoredImage) implements PlannedImage {

      @Override
      public ImageType imageType() {
        return source.imageType();
      }
    }

    /** The provider has no image of this type, and the attempt reports it as unavailable. */
    record ReportUnavailable(ImageType imageType) implements PlannedImage {}
  }

  /**
   * Plans the item's artwork: first each image type without a source, then each source in order.
   */
  public static ArtworkPlan of(
      @NonNull ArtworkSources artwork,
      @NonNull Map<ImageType, List<Image>> storedImagesByType,
      @NonNull ImageRefreshMode refreshMode) {
    var images = new ArrayList<PlannedImage>();
    for (var imageType : artwork.missingImageTypes()) {
      images.add(
          planWithoutSource(imageType, storedImagesByType.containsKey(imageType), refreshMode));
    }

    for (var source : artwork.sources()) {
      var stored = storedImagesByType.getOrDefault(source.imageType(), List.of());
      images.add(planWithSource(source, stored, refreshMode));
    }

    return new ArtworkPlan(images);
  }

  private static PlannedImage planWithoutSource(
      ImageType imageType, boolean stored, ImageRefreshMode refreshMode) {
    if (stored && refreshMode == ImageRefreshMode.PRESERVE) {
      return new PlannedImage.KeepStoredImage(imageType);
    }

    return new PlannedImage.ReportUnavailable(imageType);
  }

  private static PlannedImage planWithSource(
      ImageSource source, List<Image> stored, ImageRefreshMode refreshMode) {
    if (stored.isEmpty()) {
      return new PlannedImage.DownloadImage(source, false);
    }

    var replace =
        switch (refreshMode) {
          case PRESERVE -> false;
          case REFRESH_IF_CHANGED ->
              stored.stream().anyMatch(image -> !Objects.equals(image.getKey(), source.key()));
          case FORCE_REFRESH -> true;
        };
    if (!replace) {
      return new PlannedImage.KeepStoredImage(source.imageType());
    }

    return new PlannedImage.DownloadImage(source, true);
  }
}
