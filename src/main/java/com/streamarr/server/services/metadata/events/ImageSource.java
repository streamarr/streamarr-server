package com.streamarr.server.services.metadata.events;

import com.streamarr.server.domain.media.ImageType;

public sealed interface ImageSource {

  ImageType imageType();

  String key();

  record TmdbImageSource(ImageType imageType, String pathFragment) implements ImageSource {

    @Override
    public String key() {
      return pathFragment;
    }
  }
}
