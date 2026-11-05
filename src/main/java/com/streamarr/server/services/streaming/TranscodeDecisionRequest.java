package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a transcode decision reads. {@code videoCopySupport} lists what each connected worker can
 * deliver beyond passing a copied video stream through, including busy workers. {@code
 * directPlayRefusals} names why the format attempt does not direct-play the source file, and the
 * decision names them before its own reasons.
 */
@Builder(toBuilder = true)
public record TranscodeDecisionRequest(
    @NonNull MediaProbe source,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull Set<String> encodableVideoCodecFamilies,
    @NonNull Set<String> hardwareEncodableVideoCodecFamilies,
    @NonNull Set<String> verifiedVideoCodecFamilies,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull Set<String> excludedEncodeCodecFamilies,
    @NonNull ViewerLimits viewerLimits,
    @NonNull List<WorkerVideoCopySupport> videoCopySupport,
    @NonNull List<DecisionReason> directPlayRefusals) {

  public TranscodeDecisionRequest {
    // A codec family that a worker with a free slot encodes has a worker-verified encoder.
    verifiedVideoCodecFamilies =
        Stream.concat(verifiedVideoCodecFamilies.stream(), encodableVideoCodecFamilies.stream())
            .collect(Collectors.toUnmodifiableSet());
    hardwareEncodableVideoCodecFamilies = Set.copyOf(hardwareEncodableVideoCodecFamilies);
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
    videoCopySupport = List.copyOf(videoCopySupport);
    directPlayRefusals = List.copyOf(directPlayRefusals);
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings("java:S9438")
  public static class TranscodeDecisionRequestBuilder {
    private Set<String> hardwareEncodableVideoCodecFamilies = Set.of();
    private Set<String> verifiedVideoCodecFamilies = Set.of();
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();
    private ViewerLimits viewerLimits = ViewerLimits.none();
    private List<WorkerVideoCopySupport> videoCopySupport = List.of();
    private List<DecisionReason> directPlayRefusals = List.of();

    public TranscodeDecisionRequest build() {
      return new TranscodeDecisionRequest(
          source,
          capabilityDeclaration,
          encodableVideoCodecFamilies,
          hardwareEncodableVideoCodecFamilies,
          verifiedVideoCodecFamilies,
          videoFormatConstraint,
          excludedEncodeCodecFamilies,
          viewerLimits,
          videoCopySupport,
          directPlayRefusals);
    }
  }
}
