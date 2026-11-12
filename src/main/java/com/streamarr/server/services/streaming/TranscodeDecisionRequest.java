package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a transcode decision reads. {@code videoCopySupport} lists what each connected worker can
 * deliver beyond passing a copied video stream through, including busy workers. {@code
 * videoEncoders} are the worker-verified encoders whose encode features name the outputs beyond
 * 8-bit SDR that an encode may plan (#397), and {@code verifiedVideoEncoders} adds busy workers'
 * encoders. An encode plans no format in {@code excludedEncodeFormats}. {@code directPlayRefusals}
 * names why the format attempt does not direct-play the source file, and the decision names them
 * before its own reasons. {@code softwareContinuation} names the encode that the decision continues
 * with the software encoder after a hardware encoder failed. The decision may encode its codec
 * family alone (ADR 0044), in its range alone (ADR 0013). A codec family in {@code
 * excludedHardwareEncodeCodecFamilies} encodes with the software encoder, because its hardware
 * encoder failed earlier in the playback session, and the caller lists that family's software
 * encoders in place of its other encoders.
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
    @NonNull Set<EncodeFormat> excludedEncodeFormats,
    @NonNull Set<String> excludedHardwareEncodeCodecFamilies,
    @NonNull ViewerLimits viewerLimits,
    @NonNull List<WorkerVideoCopySupport> videoCopySupport,
    @NonNull List<WorkerVerifiedEncoder> videoEncoders,
    @NonNull List<WorkerVerifiedEncoder> verifiedVideoEncoders,
    @NonNull List<DecisionReason> directPlayRefusals,
    @NonNull Optional<SoftwareContinuation> softwareContinuation) {

  public TranscodeDecisionRequest {
    // A codec family that a worker with a free slot encodes has a worker-verified encoder.
    verifiedVideoCodecFamilies =
        Stream.concat(verifiedVideoCodecFamilies.stream(), encodableVideoCodecFamilies.stream())
            .collect(Collectors.toUnmodifiableSet());
    hardwareEncodableVideoCodecFamilies = Set.copyOf(hardwareEncodableVideoCodecFamilies);
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
    excludedEncodeFormats = Set.copyOf(excludedEncodeFormats);
    excludedHardwareEncodeCodecFamilies = Set.copyOf(excludedHardwareEncodeCodecFamilies);
    videoCopySupport = List.copyOf(videoCopySupport);
    videoEncoders = List.copyOf(videoEncoders);
    // An encoder of a worker with a free slot is a verified encoder.
    verifiedVideoEncoders =
        Stream.concat(verifiedVideoEncoders.stream(), videoEncoders.stream()).distinct().toList();
    directPlayRefusals = List.copyOf(directPlayRefusals);
  }

  /**
   * Whether an encode of this codec family runs on a software encoder: every encode of a software
   * continuation, and an encode of a codec family whose hardware encoder failed earlier in the
   * playback session (ADR 0044).
   */
  public boolean encodesInSoftware(String codecFamily) {
    return softwareContinuation.isPresent()
        || excludedHardwareEncodeCodecFamilies.contains(codecFamily);
  }

  /**
   * Whether an encode may plan this format: it did not fail earlier in the playback session, and a
   * software continuation keeps the failed encode's range in it.
   */
  public boolean mayEncode(EncodeFormat format) {
    return !excludedEncodeFormats.contains(format)
        && softwareContinuation.map(continuation -> continuation.mayEncode(format)).orElse(true);
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings("java:S9438")
  public static class TranscodeDecisionRequestBuilder {
    private Set<String> hardwareEncodableVideoCodecFamilies = Set.of();
    private Set<String> verifiedVideoCodecFamilies = Set.of();
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();
    private Set<EncodeFormat> excludedEncodeFormats = Set.of();
    private Set<String> excludedHardwareEncodeCodecFamilies = Set.of();
    private ViewerLimits viewerLimits = ViewerLimits.none();
    private List<WorkerVideoCopySupport> videoCopySupport = List.of();
    private List<WorkerVerifiedEncoder> videoEncoders = List.of();
    private List<WorkerVerifiedEncoder> verifiedVideoEncoders = List.of();
    private List<DecisionReason> directPlayRefusals = List.of();
    private Optional<SoftwareContinuation> softwareContinuation = Optional.empty();

    public TranscodeDecisionRequest build() {
      return new TranscodeDecisionRequest(
          source,
          capabilityDeclaration,
          encodableVideoCodecFamilies,
          hardwareEncodableVideoCodecFamilies,
          verifiedVideoCodecFamilies,
          videoFormatConstraint,
          excludedEncodeCodecFamilies,
          excludedEncodeFormats,
          excludedHardwareEncodeCodecFamilies,
          viewerLimits,
          videoCopySupport,
          videoEncoders,
          verifiedVideoEncoders,
          directPlayRefusals,
          softwareContinuation);
    }
  }
}
