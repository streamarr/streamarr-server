package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * An encoded variant before it is fitted to a capability entry: the codec profile it forces, the
 * displayed source picture whose aspect ratio it keeps, the largest picture it may take, the
 * source's output frame rate, the bitrate that the worker caps it at, and the range and bit depth
 * that it writes.
 */
@Builder(toBuilder = true)
public record EncodedVariantRequest(
    @NonNull EncodeCodecProfile codecProfile,
    @NonNull PictureSize source,
    @NonNull PictureSize bounds,
    @NonNull Fraction frameRate,
    long bitrate,
    @NonNull EncodeFormat format) {

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class EncodedVariantRequestBuilder {
    private EncodeFormat format = EncodeFormat.SDR_8_BIT;
  }
}
