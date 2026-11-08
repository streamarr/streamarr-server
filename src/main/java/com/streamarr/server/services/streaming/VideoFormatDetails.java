package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/** Probe facts for the source, or planned output facts after rewrites and encoding. */
@Builder
public record VideoFormatDetails(
    @NonNull Optional<VideoCodecProfile> codecProfile,
    @NonNull OptionalInt codecLevel,
    @NonNull OptionalInt bitDepth,
    @NonNull Optional<ChromaSubsampling> chromaSubsampling,
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull Optional<MasteringDisplayColorVolume> masteringDisplay,
    @NonNull Optional<ContentLightLevel> contentLightLevel,
    @NonNull List<BitstreamRewrite> bitstreamRewrites) {

  public VideoFormatDetails {
    bitstreamRewrites = List.copyOf(bitstreamRewrites);
  }

  static VideoFormatDetails from(VideoPlaybackFacts source) {
    return builder()
        .codecProfile(source.videoProperties().flatMap(VideoStreamProperties::codecProfile))
        .codecLevel(
            source
                .videoProperties()
                .map(VideoStreamProperties::codecLevel)
                .orElseGet(OptionalInt::empty))
        .bitDepth(source.bitDepth())
        .chromaSubsampling(source.chromaSubsampling())
        .dynamicRange(source.dynamicRange())
        .masteringDisplay(source.masteringDisplay())
        .contentLightLevel(source.contentLightLevel())
        .bitstreamRewrites(List.of())
        .build();
  }

  static VideoFormatDetails from(PlannedVideoOutput output) {
    return switch (output) {
      case PlannedVideoOutput.StreamCopy copy ->
          builder()
              .codecProfile(copy.codecProfile())
              .codecLevel(copy.codecLevel())
              .bitDepth(copy.bitDepth())
              .chromaSubsampling(copy.chromaSubsampling())
              .dynamicRange(outputRange(copy))
              .masteringDisplay(copy.masteringDisplay())
              .contentLightLevel(copy.contentLightLevel())
              .bitstreamRewrites(copy.bitstreamRewrites())
              .build();
      case PlannedVideoOutput.Encode encode ->
          builder()
              .codecProfile(encode.codecProfile().map(EncodeCodecProfile::codecProfile))
              .codecLevel(OptionalInt.empty())
              .bitDepth(OptionalInt.of(encode.bitDepth()))
              .chromaSubsampling(Optional.of(encode.chromaSubsampling()))
              .dynamicRange(encode.dynamicRange())
              .masteringDisplay(encode.masteringDisplay())
              .contentLightLevel(encode.contentLightLevel())
              .bitstreamRewrites(List.of())
              .build();
    };
  }

  private static VideoDynamicRange outputRange(PlannedVideoOutput.StreamCopy copy) {
    var range = copy.dynamicRange();
    if (copy.dolbyVisionOutput().isEmpty()
        || !(range.dolbyVision() instanceof DolbyVision.Present present)) {
      return range;
    }

    var output = copy.dolbyVisionOutput().orElseThrow();
    var compatibility =
        switch (output.profile()) {
          case PROFILE_5, PROFILE_10_0 -> 0;
          case PROFILE_8_1, PROFILE_10_1 -> 1;
          case PROFILE_8_4, PROFILE_10_4 -> 4;
        };
    var configuration =
        DolbyVisionConfiguration.builder()
            .profile(OptionalInt.of(output.profile().profileNumber()))
            .level(OptionalInt.of(output.level()))
            .baseLayerCompatibilityId(OptionalInt.of(compatibility))
            .baseLayer(true)
            .rpu(true)
            .build();
    return new VideoDynamicRange(
        range.compatibleBase(),
        range.hdr10Plus(),
        present.toBuilder().declared(Optional.of(configuration)).build());
  }
}
