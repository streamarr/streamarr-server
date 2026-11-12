package com.streamarr.server.domain.media;

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
