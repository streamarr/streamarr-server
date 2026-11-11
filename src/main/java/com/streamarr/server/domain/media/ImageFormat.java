package com.streamarr.server.domain.media;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** An image format that the server stores, recognised by the first bytes of a file. */
@RequiredArgsConstructor
public enum ImageFormat {
  JPEG("jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
  PNG("png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});

  @Getter private final String extension;
  @Getter private final String mediaType;
  private final byte[] signature;

  /** The format whose signature the bytes start with, or empty when they start with neither. */
  public static Optional<ImageFormat> ofBytes(@NonNull byte[] imageData) {
    return Arrays.stream(values()).filter(format -> format.isFormatOf(imageData)).findFirst();
  }

  private boolean isFormatOf(byte[] imageData) {
    return imageData.length >= signature.length
        && Arrays.equals(imageData, 0, signature.length, signature, 0, signature.length);
  }
}
