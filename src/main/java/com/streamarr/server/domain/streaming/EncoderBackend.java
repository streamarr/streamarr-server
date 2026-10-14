package com.streamarr.server.domain.streaming;

import lombok.Builder;
import lombok.NonNull;

/**
 * The encoder that a worker runs for a codec family, such as {@code libx264} or {@code h264_nvenc}
 * for {@code h264}. Every job attempt of a variant must use the encoder backend of the variant's
 * first job attempt, because the backend decides the variant's initialization segment.
 */
@Builder
public record EncoderBackend(
    @NonNull String codecFamily, @NonNull String encoder, @NonNull Kind kind) {

  public enum Kind {
    HARDWARE,
    SOFTWARE
  }
}
