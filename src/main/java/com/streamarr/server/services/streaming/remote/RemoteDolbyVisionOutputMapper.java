package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.transcode.v1.DolbyVisionSampleEntry;

// Maps a copy's Dolby Vision output to the worker contract (ADR 0013). Domain and protobuf
// contracts intentionally share the simple name.
final class RemoteDolbyVisionOutputMapper {

  private RemoteDolbyVisionOutputMapper() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.DolbyVisionOutput toProto(DolbyVisionOutput output) {
    var profile = output.profile();
    return com.streamarr.transcode.v1.DolbyVisionOutput.newBuilder()
        .setProfile(profile.profileNumber())
        .setLevel(output.level())
        .setBaseLayerCompatibilityId(baseLayerCompatibilityId(profile))
        .setSampleEntry(sampleEntry(profile))
        .build();
  }

  // bl_signal_compatibility_id: 0 for no compatible base, 1 for PQ and 4 for HLG.
  private static int baseLayerCompatibilityId(DolbyVisionProfile profile) {
    return switch (profile) {
      case PROFILE_5, PROFILE_10_0 -> 0;
      case PROFILE_8_1, PROFILE_10_1 -> 1;
      case PROFILE_8_4, PROFILE_10_4 -> 4;
    };
  }

  // Profiles with a compatible base keep their base sample entry, so the playlist's CODECS names
  // the base (ADR 0013).
  private static DolbyVisionSampleEntry sampleEntry(DolbyVisionProfile profile) {
    return switch (profile) {
      case PROFILE_5 -> DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DVH1;
      case PROFILE_8_1, PROFILE_8_4 -> DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_HVC1;
      case PROFILE_10_0 -> DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_DAV1;
      case PROFILE_10_1, PROFILE_10_4 -> DolbyVisionSampleEntry.DOLBY_VISION_SAMPLE_ENTRY_AV01;
    };
  }
}
