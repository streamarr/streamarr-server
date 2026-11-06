package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.transcode.v1.CompatibleBase;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoMode;
import java.util.Optional;
import java.util.Set;

// An older worker refuses an encode whose output is not 8-bit SDR, and an encoder that passed the
// 8-bit test encode can still fail a 10-bit one. Each such encode therefore goes only to a worker
// whose encoder of the codec family lists every feature the encode needs (#397).
final class RemoteVideoEncodeRequirements {

  private RemoteVideoEncodeRequirements() {}

  static Set<VideoEncodeFeature> features(VideoDecision video) {
    if (video.getMode() != VideoMode.VIDEO_MODE_ENCODE) {
      return Set.of();
    }

    return switch (video.getOutputDynamicRange().getCompatibleBase()) {
      case COMPATIBLE_BASE_PQ -> pqFeaturesFrom(video.getSourceDynamicRange().getCompatibleBase());
      case COMPATIBLE_BASE_HLG -> Set.of(VideoEncodeFeature.HLG);
      case COMPATIBLE_BASE_UNSPECIFIED, COMPATIBLE_BASE_NONE, COMPATIBLE_BASE_SDR, UNRECOGNIZED ->
          sdrFeaturesAt(video.getOutputBitDepth());
    };
  }

  private static Set<VideoEncodeFeature> pqFeaturesFrom(CompatibleBase sourceBase) {
    if (sourceBase == CompatibleBase.COMPATIBLE_BASE_NONE) {
      return Set.of(VideoEncodeFeature.PQ, VideoEncodeFeature.DOLBY_VISION_TO_PQ);
    }

    return Set.of(VideoEncodeFeature.PQ);
  }

  private static Set<VideoEncodeFeature> sdrFeaturesAt(int outputBitDepth) {
    if (outputBitDepth > 8) {
      return Set.of(VideoEncodeFeature.TEN_BIT);
    }

    return Set.of();
  }

  // Empty for a feature that this server does not know yet, which no decision needs.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Optional<VideoEncodeFeature> fromProto(
      com.streamarr.transcode.v1.VideoEncodeFeature feature) {
    return switch (feature) {
      case VIDEO_ENCODE_FEATURE_TEN_BIT -> Optional.of(VideoEncodeFeature.TEN_BIT);
      case VIDEO_ENCODE_FEATURE_PQ -> Optional.of(VideoEncodeFeature.PQ);
      case VIDEO_ENCODE_FEATURE_HLG -> Optional.of(VideoEncodeFeature.HLG);
      case VIDEO_ENCODE_FEATURE_DOLBY_VISION_TO_PQ ->
          Optional.of(VideoEncodeFeature.DOLBY_VISION_TO_PQ);
      case VIDEO_ENCODE_FEATURE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }
}
