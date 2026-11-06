package com.streamarr.server.domain.streaming.headroom;

import lombok.NonNull;

/**
 * What a job attempt that encodes video runs, apart from its pixel rates: one encoder and preset,
 * one source decoder on one decode backend, and one tone-mapping chain. The server validates its
 * predictions per encode configuration (ADR 0044).
 */
public record EncodeConfiguration(
    @NonNull EncoderAndPreset encoderAndPreset,
    @NonNull SourceDecoder sourceDecoder,
    @NonNull ToneMapping toneMapping) {

  /** The same configuration with another encoder and preset. */
  public EncodeConfiguration withEncoderAndPreset(@NonNull EncoderAndPreset other) {
    return new EncodeConfiguration(other, sourceDecoder, toneMapping);
  }
}
