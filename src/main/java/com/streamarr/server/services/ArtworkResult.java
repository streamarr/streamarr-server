package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageType;

/** What happened to one requested source image; each source image yields exactly one result. */
public sealed interface ArtworkResult {

  ImageType imageType();

  record Saved(ImageType imageType) implements ArtworkResult {}

  /** Stored artwork was kept because the refresh mode did not require the provider's image. */
  record Skipped(ImageType imageType) implements ArtworkResult {}

  /** The provider has no image of this type for the entity. */
  record Unavailable(ImageType imageType) implements ArtworkResult {}

  record Failed(ImageType imageType, Throwable cause) implements ArtworkResult {}
}
