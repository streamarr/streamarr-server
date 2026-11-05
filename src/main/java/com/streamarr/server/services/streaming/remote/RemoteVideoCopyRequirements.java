package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.VideoSampleEntry;
import com.streamarr.transcode.v1.VideoCopyFeature;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoMode;
import java.util.Optional;
import java.util.Set;

// A worker that predates a copy feature refuses a copy that sets output_dynamic_range, and it
// ignores
// the other planned-output fields and would deliver the copy unchanged. Each such copy therefore
// goes only to a worker that lists every feature it needs (ADR 0013, ADR 0036).
final class RemoteVideoCopyRequirements {

  private RemoteVideoCopyRequirements() {}

  static Set<Integer> features(VideoDecision video) {
    if (video.getMode() != VideoMode.VIDEO_MODE_COPY || !carriesPlannedOutput(video)) {
      return Set.of();
    }

    var plannedOutput = VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT_VALUE;
    return switch (video.getDolbyVisionOutput().getSampleEntry()) {
      case DOLBY_VISION_SAMPLE_ENTRY_DVH1 ->
          Set.of(plannedOutput, VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY_VALUE);
      case DOLBY_VISION_SAMPLE_ENTRY_DAV1 ->
          Set.of(plannedOutput, VideoCopyFeature.VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY_VALUE);
      case DOLBY_VISION_SAMPLE_ENTRY_UNSPECIFIED,
          DOLBY_VISION_SAMPLE_ENTRY_HVC1,
          DOLBY_VISION_SAMPLE_ENTRY_AV01,
          UNRECOGNIZED ->
          Set.of(plannedOutput);
    };
  }

  // The Dolby Vision sample entry that a worker listing the feature writes; empty for every other
  // feature, including one that this server does not know.
  static Optional<VideoSampleEntry> dolbyVisionSampleEntry(int featureValue) {
    return Optional.ofNullable(VideoCopyFeature.forNumber(featureValue))
        .flatMap(RemoteVideoCopyRequirements::dolbyVisionSampleEntry);
  }

  private static Optional<VideoSampleEntry> dolbyVisionSampleEntry(VideoCopyFeature feature) {
    return switch (feature) {
      case VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY -> Optional.of(VideoSampleEntry.DVH1);
      case VIDEO_COPY_FEATURE_DAV1_SAMPLE_ENTRY -> Optional.of(VideoSampleEntry.DAV1);
      case VIDEO_COPY_FEATURE_UNSPECIFIED, VIDEO_COPY_FEATURE_PLANNED_OUTPUT, UNRECOGNIZED ->
          Optional.empty();
    };
  }

  private static boolean carriesPlannedOutput(VideoDecision video) {
    return video.hasOutputDynamicRange()
        || video.hasDolbyVisionOutput()
        || video.hasMasteringDisplay()
        || video.hasContentLightLevel();
  }
}
