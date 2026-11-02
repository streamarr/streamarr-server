package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The audio stream facts that probe version 7 adds. {@code channelLayout} and {@code sampleFormat}
 * are ffprobe's {@code channel_layout} and {@code sample_fmt} text, unchanged, and are empty when
 * ffprobe names none. The six flags are ffprobe's final dispositions of the same names; {@code
 * StreamInfo.comment} holds the seventh, because subtitle streams report it too. {@code
 * flacMinimumBlockSizeSamples} is the first field of a FLAC stream's STREAMINFO block, and is empty
 * on any other stream and when the probe found no 34-byte STREAMINFO block.
 */
@Builder
public record AudioStreamProperties(
    @NonNull Optional<String> channelLayout,
    @NonNull Optional<String> sampleFormat,
    boolean visualImpaired,
    boolean descriptions,
    boolean original,
    boolean dub,
    boolean cleanEffects,
    boolean dependent,
    @NonNull OptionalInt flacMinimumBlockSizeSamples) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioStreamPropertiesBuilder {
    private Optional<String> channelLayout = Optional.empty();
    private Optional<String> sampleFormat = Optional.empty();
    private OptionalInt flacMinimumBlockSizeSamples = OptionalInt.empty();
  }
}
