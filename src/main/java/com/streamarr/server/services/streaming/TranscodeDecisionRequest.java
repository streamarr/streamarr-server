package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.MediaProbe;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record TranscodeDecisionRequest(
    @NonNull MediaProbe source,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull Set<String> encodableVideoCodecFamilies,
    @NonNull Set<String> hardwareEncodableVideoCodecFamilies,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull Set<String> excludedEncodeCodecFamilies,
    @NonNull ViewerLimits viewerLimits) {

  public TranscodeDecisionRequest {
    hardwareEncodableVideoCodecFamilies = Set.copyOf(hardwareEncodableVideoCodecFamilies);
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings("java:S9438")
  public static class TranscodeDecisionRequestBuilder {
    private Set<String> hardwareEncodableVideoCodecFamilies = Set.of();
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();
    private ViewerLimits viewerLimits = ViewerLimits.none();

    public TranscodeDecisionRequest build() {
      return new TranscodeDecisionRequest(
          source,
          capabilityDeclaration,
          encodableVideoCodecFamilies,
          hardwareEncodableVideoCodecFamilies,
          videoFormatConstraint,
          excludedEncodeCodecFamilies,
          viewerLimits);
    }
  }
}
