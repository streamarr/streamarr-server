package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/** The video that a transcode decision plans to deliver: a stream copy or an encode (ADR 0013). */
public sealed interface PlannedVideoOutput {

  VideoDynamicRange dynamicRange();

  /**
   * A stream copy of the chosen video stream. {@code codecProfile} and {@code codecLevel} are what
   * probe version 5 and later report, and are empty for a probe version 4 outcome. {@code
   * bitstreamRewrites} lists every change that the worker makes to the copied stream.
   */
  @Builder
  record StreamCopy(
      @NonNull VideoDynamicRange dynamicRange,
      @NonNull Optional<VideoCodecProfile> codecProfile,
      @NonNull OptionalInt codecLevel,
      @NonNull List<BitstreamRewrite> bitstreamRewrites)
      implements PlannedVideoOutput {

    public StreamCopy {
      bitstreamRewrites = List.copyOf(bitstreamRewrites);
    }

    /** The copy of the chosen video stream, without bitstream rewrites. */
    public static StreamCopy of(@NonNull VideoPlaybackFacts source) {
      var properties = source.videoProperties();
      return StreamCopy.builder()
          .dynamicRange(source.dynamicRange())
          .codecProfile(properties.flatMap(VideoStreamProperties::codecProfile))
          .codecLevel(
              properties.map(VideoStreamProperties::codecLevel).orElseGet(OptionalInt::empty))
          .bitstreamRewrites(List.of())
          .build();
    }
  }

  /** An encode, in the codec profile that it forces. */
  record Encode(
      @NonNull Optional<EncodeCodecProfile> codecProfile, @NonNull VideoDynamicRange dynamicRange)
      implements PlannedVideoOutput {

    /** Every encode is SDR until the server encodes HDR (#397). */
    public static Encode sdr(@NonNull Optional<EncodeCodecProfile> codecProfile) {
      return new Encode(codecProfile, VideoDynamicRange.SDR);
    }
  }
}
