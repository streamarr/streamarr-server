package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.NonNull;

public sealed interface ProbeOutcome {

  record Failure(@NonNull ProbeError error) implements ProbeOutcome {}

  record Success(
      @NonNull ProbeContainer container,
      @NonNull List<StreamInfo> streams,
      @NonNull KeyframeKnowledge keyframes)
      implements ProbeOutcome {

    public Success {
      streams = List.copyOf(streams);
    }

    /** An outcome whose probe version does not report keyframe positions. */
    public Success(@NonNull ProbeContainer container, @NonNull List<StreamInfo> streams) {
      this(container, streams, new KeyframeKnowledge.NotReported());
    }

    public Success withKeyframes(@NonNull KeyframeKnowledge knowledge) {
      return new Success(container, streams, knowledge);
    }

    public MediaProbe mediaProbe() {
      var video =
          streams.stream().filter(s -> "video".equals(s.codecType())).findFirst().orElseThrow();
      var audio = streams.stream().filter(s -> "audio".equals(s.codecType())).findFirst();
      return MediaProbe.builder()
          .duration(container.duration().orElse(Duration.ZERO))
          .bitrate(container.bitrate().orElse(0))
          .containerFormat(container.format())
          .videoCodec(video.codec().orElse(null))
          .width(video.width().orElse(0))
          .height(video.height().orElse(0))
          .framerate(video.framerate())
          .audioCodec(audio.flatMap(StreamInfo::codec).orElse(null))
          .audioChannels(audio.map(StreamInfo::channels).orElse(OptionalInt.empty()))
          .audioBitrate(audio.map(StreamInfo::bitrate).orElse(OptionalLong.empty()))
          .streams(streams)
          .build();
    }
  }
}
