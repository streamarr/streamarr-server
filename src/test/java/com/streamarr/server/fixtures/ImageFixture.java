package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import java.util.UUID;

public final class ImageFixture {

  private ImageFixture() {}

  public static Image.ImageBuilder<?, ?> imageBuilder(UUID entityId) {
    return Image.builder()
        .entityId(entityId)
        .entityType(ImageEntityType.MOVIE)
        .imageType(ImageType.POSTER)
        .variant(ImageSize.SMALL)
        .width(185)
        .height(278)
        .contentSha256("a".repeat(64));
  }
}
