package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * One stream of a probed source. The facts from {@code dynamicRange} to {@code hearingImpaired}
 * come from probe version 4 and later. An earlier probe version leaves them empty. {@code
 * videoProperties} comes from probe version 5 and later, for video streams only. {@code comment},
 * ffprobe's commentary disposition, comes from probe version 7 and later, for audio and subtitle
 * streams only. {@code audioProperties} comes from probe version 7 and later, for audio streams
 * only. An empty {@code comment} counts as false, and so does each disposition of an empty {@code
 * audioProperties}. {@code sampleEntry} comes from probe version 7 and later, for video streams
 * whose MP4 or MOV file stores one. {@code maximumFrameRate}, the highest rate that the stream's
 * presentation times show over 1 to 24 consecutive frame intervals, comes from probe version 8 and
 * later, for the chosen progressive video stream when the probe measured it; empty means unknown.
 */
@Builder(toBuilder = true)
public record StreamInfo(
    int index,
    String codecType,
    @NonNull Optional<String> codec,
    @NonNull Optional<String> language,
    @NonNull OptionalInt channels,
    @NonNull OptionalLong bitrate,
    @NonNull OptionalInt width,
    @NonNull OptionalInt height,
    @NonNull OptionalDouble framerate,
    boolean isDefault,
    boolean isForced,
    @NonNull Optional<VideoDynamicRange> dynamicRange,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<ChromaSubsampling> chromaSubsampling,
    @NonNull OptionalInt rotationDegrees,
    @NonNull Optional<ContainerCropping> containerCropping,
    @NonNull Optional<String> codecString,
    @NonNull OptionalInt sampleRate,
    @NonNull Optional<String> audioProfile,
    @NonNull Optional<Duration> endTime,
    @NonNull Optional<MasteringDisplayColorVolume> masteringDisplay,
    @NonNull Optional<ContentLightLevel> contentLightLevel,
    @NonNull Optional<String> title,
    @NonNull Optional<String> handlerName,
    @NonNull Optional<Boolean> hearingImpaired,
    @NonNull Optional<VideoStreamProperties> videoProperties,
    @NonNull Optional<Boolean> comment,
    @NonNull Optional<AudioStreamProperties> audioProperties,
    @NonNull Optional<String> sampleEntry,
    @NonNull Optional<Fraction> maximumFrameRate) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class StreamInfoBuilder {
    private Optional<String> codec = Optional.empty();
    private Optional<String> language = Optional.empty();
    private OptionalInt channels = OptionalInt.empty();
    private OptionalLong bitrate = OptionalLong.empty();
    private OptionalInt width = OptionalInt.empty();
    private OptionalInt height = OptionalInt.empty();
    private OptionalDouble framerate = OptionalDouble.empty();
    private Optional<VideoDynamicRange> dynamicRange = Optional.empty();
    private OptionalInt bitDepth = OptionalInt.empty();
    private Optional<ChromaSubsampling> chromaSubsampling = Optional.empty();
    private OptionalInt rotationDegrees = OptionalInt.empty();
    private Optional<ContainerCropping> containerCropping = Optional.empty();
    private Optional<String> codecString = Optional.empty();
    private OptionalInt sampleRate = OptionalInt.empty();
    private Optional<String> audioProfile = Optional.empty();
    private Optional<Duration> endTime = Optional.empty();
    private Optional<MasteringDisplayColorVolume> masteringDisplay = Optional.empty();
    private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
    private Optional<String> title = Optional.empty();
    private Optional<String> handlerName = Optional.empty();
    private Optional<Boolean> hearingImpaired = Optional.empty();
    private Optional<VideoStreamProperties> videoProperties = Optional.empty();
    private Optional<Boolean> comment = Optional.empty();
    private Optional<AudioStreamProperties> audioProperties = Optional.empty();
    private Optional<String> sampleEntry = Optional.empty();
    private Optional<Fraction> maximumFrameRate = Optional.empty();
  }
}
