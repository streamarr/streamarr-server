package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record TranscodeDecisionRequest(
    @NonNull MediaProbe source,
    @NonNull StreamingOptions clientOptions,
    @NonNull Set<String> encodableVideoCodecFamilies,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull Set<String> excludedEncodeCodecFamilies) {

  public TranscodeDecisionRequest {
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
  }

  public static class TranscodeDecisionRequestBuilder {
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();

    public TranscodeDecisionRequest build() {
      return new TranscodeDecisionRequest(
          source,
          clientOptions,
          encodableVideoCodecFamilies,
          videoFormatConstraint,
          excludedEncodeCodecFamilies);
    }
  }
}
