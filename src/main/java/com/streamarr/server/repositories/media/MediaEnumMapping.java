package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.util.List;

/** Maps the media enums to the generated jOOQ enums that share their names and back. */
final class MediaEnumMapping {

  private MediaEnumMapping() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.server.jooq.generated.enums.ImageEntityType toJooq(
      ImageEntityType itemType) {
    return com.streamarr.server.jooq.generated.enums.ImageEntityType.lookupLiteral(itemType.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.server.jooq.generated.enums.ImageType toJooq(ImageType imageType) {
    return com.streamarr.server.jooq.generated.enums.ImageType.lookupLiteral(imageType.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.server.jooq.generated.enums.ImageType[] toJooq(List<ImageType> imageTypes) {
    return imageTypes.stream()
        .map(MediaEnumMapping::toJooq)
        .toArray(com.streamarr.server.jooq.generated.enums.ImageType[]::new);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static ImageEntityType toDomain(
      com.streamarr.server.jooq.generated.enums.ImageEntityType itemType) {
    return ImageEntityType.valueOf(itemType.getLiteral());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static ImageType toDomain(com.streamarr.server.jooq.generated.enums.ImageType imageType) {
    return ImageType.valueOf(imageType.getLiteral());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.server.jooq.generated.enums.ImageRefreshMode toJooq(
      ImageRefreshMode refreshMode) {
    return com.streamarr.server.jooq.generated.enums.ImageRefreshMode.lookupLiteral(
        refreshMode.name());
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static ImageRefreshMode toDomain(
      com.streamarr.server.jooq.generated.enums.ImageRefreshMode refreshMode) {
    return ImageRefreshMode.valueOf(refreshMode.getLiteral());
  }
}
