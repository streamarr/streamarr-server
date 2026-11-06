package com.streamarr.server.domain.streaming;

import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * An encoder backend that a worker advertises after its test encode passed. {@code
 * keyframeVerified} is true only when ADR 0037 lists the encoder as keyframe-verified. {@code
 * encodeFeatures} lists the outputs beyond 8-bit 4:2:0 SDR that also passed the worker's own test
 * encode (#397).
 */
@Builder
public record WorkerVerifiedEncoder(
    @NonNull EncoderBackend backend,
    boolean keyframeVerified,
    @NonNull Set<VideoEncodeFeature> encodeFeatures) {

  public WorkerVerifiedEncoder {
    encodeFeatures = Set.copyOf(encodeFeatures);
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class WorkerVerifiedEncoderBuilder {
    private Set<VideoEncodeFeature> encodeFeatures = Set.of();
  }
}
