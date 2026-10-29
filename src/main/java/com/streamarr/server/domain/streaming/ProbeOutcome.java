package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

public sealed interface ProbeOutcome {

  /** Whether a worker must scan the source's packets to find its keyframe positions. */
  default boolean needsKeyframePacketScan() {
    return this instanceof Success success
        && success.keyframes() instanceof KeyframeKnowledge.PacketScanRequired;
  }

  record Failure(@NonNull ProbeError error) implements ProbeOutcome {}

  /**
   * A successful probe. {@code chosenVideoStream} is empty for a probe version that names no chosen
   * video stream, before version 4.
   */
  @Builder
  record Success(
      @NonNull ProbeContainer container,
      @NonNull List<StreamInfo> streams,
      @NonNull KeyframeKnowledge keyframes,
      @NonNull Optional<ChosenVideoStream> chosenVideoStream,
      @NonNull Optional<Boolean> streamTitlesOmitted)
      implements ProbeOutcome {

    public Success {
      streams = List.copyOf(streams);
    }

    /** An outcome of a probe version that names no chosen video stream. */
    public Success(
        @NonNull ProbeContainer container,
        @NonNull List<StreamInfo> streams,
        @NonNull KeyframeKnowledge keyframes) {
      this(container, streams, keyframes, Optional.empty(), Optional.empty());
    }

    public MediaProbe mediaProbe() {
      var video = videoStream();
      var audio = streams.stream().filter(s -> "audio".equals(s.codecType())).findFirst();
      return MediaProbe.builder()
          .duration(container.duration().orElse(Duration.ZERO))
          .bitrate(container.bitrate().orElse(0))
          .containerFormat(container.format())
          .videoCodec(video.codec().orElse(null))
          .width(video.width().orElse(0))
          .height(video.height().orElse(0))
          .framerate(framerate(video))
          .audioCodec(audio.flatMap(StreamInfo::codec).orElse(null))
          .audioChannels(audio.map(StreamInfo::channels).orElse(OptionalInt.empty()))
          .audioBitrate(audio.map(StreamInfo::bitrate).orElse(OptionalLong.empty()))
          .streams(streams)
          .keyframes(keyframes)
          .videoPlaybackFacts(
              chosenVideoStream.map(
                  chosen ->
                      VideoPlaybackFacts.builder()
                          .streamIndex(chosen.streamIndex())
                          .codec(video.codec().map(ProbedVideoCodec::fromProbedName))
                          .dynamicRange(video.dynamicRange().orElse(VideoDynamicRange.UNSPECIFIED))
                          .bitDepth(video.bitDepth())
                          .chromaSubsampling(video.chromaSubsampling())
                          .codecString(video.codecString())
                          .rotationDegrees(video.rotationDegrees())
                          .containerCropping(video.containerCropping())
                          .frameRateRule(chosen.frameRateRule())
                          .videoProperties(video.videoProperties())
                          .build()))
          .build();
    }

    // An encode runs at the frame-rate rule's output frame rate, so a refused rule leaves none.
    private OptionalDouble framerate(StreamInfo video) {
      if (chosenVideoStream.isEmpty()) {
        return video.framerate();
      }

      return chosenVideoStream
          .get()
          .frameRateRule()
          .outputFrameRate()
          .map(rate -> OptionalDouble.of(rate.toDouble()))
          .orElse(OptionalDouble.empty());
    }

    // Before probe version 4, an outcome names no chosen video stream, and the transcode decision
    // read the first video stream.
    private StreamInfo videoStream() {
      if (chosenVideoStream.isEmpty()) {
        return streams.stream()
            .filter(s -> "video".equals(s.codecType()))
            .findFirst()
            .orElseThrow();
      }

      var index = chosenVideoStream.get().streamIndex();
      return streams.stream().filter(s -> s.index() == index).findFirst().orElseThrow();
    }

    @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
    public static class SuccessBuilder {
      private Optional<ChosenVideoStream> chosenVideoStream = Optional.empty();
      private Optional<Boolean> streamTitlesOmitted = Optional.empty();
    }
  }
}
