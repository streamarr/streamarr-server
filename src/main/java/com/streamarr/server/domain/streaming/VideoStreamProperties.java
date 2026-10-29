package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * What probe version 5 and later report about a video stream's coding and color signaling, and
 * whether the probe read the stream's first frames. An empty value is one that the probe could not
 * name: an empty {@code codecProfile} is the contract's UNSPECIFIED codec profile. {@code
 * codecLevel} is major × 10 + minor. A Presence on the stream is ABSENT only when {@code
 * firstFramesRead} holds. An omitted flag says that the stream carries, or may carry, that half of
 * its static HDR metadata although the probe left it empty, so a stream with either flag set is
 * never PQ10. Without {@code firstFramesRead}, an empty mastering display or content light level
 * says only that the container declares none, so it proves no PQ10 either. {@code
 * firstPacketsCarryPresentationTimes} tells whether every packet that the probe read of the chosen
 * video stream carried a presentation time, and is empty when the probe read no packets.
 */
@Builder
public record VideoStreamProperties(
    @NonNull Optional<VideoCodecProfile> codecProfile,
    @NonNull OptionalInt codecLevel,
    @NonNull Optional<VideoCodecTier> codecTier,
    @NonNull Optional<ColorPrimaries> colorPrimaries,
    @NonNull Optional<TransferCharacteristics> transferCharacteristics,
    @NonNull Optional<MatrixCoefficients> matrixCoefficients,
    @NonNull Optional<ColorRange> colorRange,
    boolean firstFramesRead,
    boolean masteringDisplayOmitted,
    boolean contentLightLevelOmitted,
    @NonNull Optional<Boolean> firstPacketsCarryPresentationTimes) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class VideoStreamPropertiesBuilder {
    private Optional<VideoCodecProfile> codecProfile = Optional.empty();
    private OptionalInt codecLevel = OptionalInt.empty();
    private Optional<VideoCodecTier> codecTier = Optional.empty();
    private Optional<ColorPrimaries> colorPrimaries = Optional.empty();
    private Optional<TransferCharacteristics> transferCharacteristics = Optional.empty();
    private Optional<MatrixCoefficients> matrixCoefficients = Optional.empty();
    private Optional<ColorRange> colorRange = Optional.empty();
    private Optional<Boolean> firstPacketsCarryPresentationTimes = Optional.empty();
  }
}
