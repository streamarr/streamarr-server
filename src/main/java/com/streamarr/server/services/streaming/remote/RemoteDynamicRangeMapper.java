package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.transcode.v1.DolbyVisionAbsent;
import com.streamarr.transcode.v1.DolbyVisionPresent;

// Writes a dynamic range into the job contract, which maps Dolby Vision to a oneof whose unset
// arm means unspecified (ADR 0036). Domain and protobuf contracts intentionally share simple names.
final class RemoteDynamicRangeMapper {

  private RemoteDynamicRangeMapper() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static com.streamarr.transcode.v1.VideoDynamicRange toProto(VideoDynamicRange range) {
    var proto =
        com.streamarr.transcode.v1.VideoDynamicRange.newBuilder()
            .setCompatibleBase(compatibleBase(range.compatibleBase()))
            .setHdr10Plus(presence(range.hdr10Plus()));
    switch (range.dolbyVision()) {
      case DolbyVision.Unspecified _ -> proto.clearDolbyVision();
      case DolbyVision.Absent _ ->
          proto.setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
      case DolbyVision.Present(
              var declared,
              var observedRpu,
              var firstRpuHeader,
              var rpuPlacement) -> {
        var present =
            DolbyVisionPresent.newBuilder()
                .setObservedRpu(presence(observedRpu))
                .setRpuPlacement(rpuPlacement(rpuPlacement));
        declared.map(RemoteDynamicRangeMapper::configuration).ifPresent(present::setDeclared);
        firstRpuHeader
            .map(RemoteDynamicRangeMapper::rpuHeader)
            .ifPresent(present::setFirstRpuHeader);
        proto.setDolbyVisionPresent(present);
      }
    }

    return proto.build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionConfiguration configuration(
      DolbyVisionConfiguration configuration) {
    var proto =
        com.streamarr.transcode.v1.DolbyVisionConfiguration.newBuilder()
            .setBaseLayer(configuration.baseLayer())
            .setEnhancementLayer(configuration.enhancementLayer())
            .setRpu(configuration.rpu());
    configuration.profile().ifPresent(proto::setProfile);
    configuration.level().ifPresent(proto::setLevel);
    configuration.baseLayerCompatibilityId().ifPresent(proto::setBaseLayerCompatibilityId);
    return proto.build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuHeader rpuHeader(
      DolbyVisionRpuHeader header) {
    return com.streamarr.transcode.v1.DolbyVisionRpuHeader.newBuilder()
        .setVdrRpuProfile(header.vdrRpuProfile())
        .setBaseLayerFullRange(header.baseLayerFullRange())
        .setEnhancementLayerSpatialResamplingFilter(
            header.enhancementLayerSpatialResamplingFilter())
        .setDisableResidual(header.disableResidual())
        .setVdrBitDepth(header.vdrBitDepth())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.DolbyVisionRpuPlacement rpuPlacement(
      DolbyVisionRpuPlacement placement) {
    return switch (placement) {
      case UNSPECIFIED ->
          com.streamarr.transcode.v1.DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED;
      case AFTER_OWN_PICTURE ->
          com.streamarr.transcode.v1.DolbyVisionRpuPlacement
              .DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE;
      case BEFORE_NEXT_PICTURE ->
          com.streamarr.transcode.v1.DolbyVisionRpuPlacement
              .DOLBY_VISION_RPU_PLACEMENT_BEFORE_NEXT_PICTURE;
      case BEFORE_OWN_PICTURE ->
          com.streamarr.transcode.v1.DolbyVisionRpuPlacement
              .DOLBY_VISION_RPU_PLACEMENT_BEFORE_OWN_PICTURE;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.CompatibleBase compatibleBase(CompatibleBase base) {
    return switch (base) {
      case UNSPECIFIED -> com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
      case NONE -> com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_NONE;
      case SDR -> com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_SDR;
      case PQ -> com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_PQ;
      case HLG -> com.streamarr.transcode.v1.CompatibleBase.COMPATIBLE_BASE_HLG;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.transcode.v1.Presence presence(Presence presence) {
    return switch (presence) {
      case UNSPECIFIED -> com.streamarr.transcode.v1.Presence.PRESENCE_UNSPECIFIED;
      case ABSENT -> com.streamarr.transcode.v1.Presence.PRESENCE_ABSENT;
      case PRESENT -> com.streamarr.transcode.v1.Presence.PRESENCE_PRESENT;
    };
  }
}
