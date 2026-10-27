package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.jooq.generated.tables.records.MediaFileStreamInfoRecord;
import java.time.Duration;
import java.util.OptionalInt;

// Writes the stream facts that probe versions 4 and 5 add into a stream row. An unspecified RPU
// sample layout is stored as NULL.
final class StreamPlaybackFactsRow {

  private StreamPlaybackFactsRow() {}

  static void set(MediaFileStreamInfoRecord row, StreamInfo stream) {
    stream.dynamicRange().ifPresent(range -> setDynamicRange(row, range));
    row.setBitDepth(nullable(stream.bitDepth()));
    row.setChromaSubsampling(stream.chromaSubsampling().map(Enum::name).orElse(null));
    row.setRotationDegrees(nullable(stream.rotationDegrees()));
    stream
        .containerCropping()
        .ifPresent(
            crop -> {
              row.setCropTop(crop.top());
              row.setCropBottom(crop.bottom());
              row.setCropLeft(crop.left());
              row.setCropRight(crop.right());
            });
    row.setCodecString(stream.codecString().orElse(null));
    row.setSampleRateHz(nullable(stream.sampleRate()));
    row.setAudioProfile(stream.audioProfile().orElse(null));
    row.setEndTimeSeconds(stream.endTime().map(Duration::getSeconds).orElse(null));
    row.setEndTimeNanos(stream.endTime().map(Duration::getNano).orElse(null));
    stream.masteringDisplay().ifPresent(display -> setMasteringDisplay(row, display));
    row.setMaxContentLightLevel(
        stream.contentLightLevel().map(ContentLightLevel::maxContentLightLevel).orElse(null));
    row.setMaxFrameAverageLightLevel(
        stream.contentLightLevel().map(ContentLightLevel::maxFrameAverageLightLevel).orElse(null));
    row.setTitle(stream.title().orElse(null));
    row.setHandlerName(stream.handlerName().orElse(null));
    row.setHearingImpaired(stream.hearingImpaired().orElse(null));
    stream.videoProperties().ifPresent(properties -> setVideoProperties(row, properties));
  }

  private static void setVideoProperties(
      MediaFileStreamInfoRecord row, VideoStreamProperties properties) {
    row.setCodecProfile(properties.codecProfile().map(Enum::name).orElse(null));
    row.setCodecLevel(nullable(properties.codecLevel()));
    row.setCodecTier(properties.codecTier().map(Enum::name).orElse(null));
    row.setColorPrimaries(properties.colorPrimaries().map(Enum::name).orElse(null));
    row.setTransferCharacteristics(
        properties.transferCharacteristics().map(Enum::name).orElse(null));
    row.setMatrixCoefficients(properties.matrixCoefficients().map(Enum::name).orElse(null));
    row.setColorRange(properties.colorRange().map(Enum::name).orElse(null));
    row.setFirstFramesRead(properties.firstFramesRead());
    row.setMasteringDisplayOmitted(properties.masteringDisplayOmitted());
    row.setContentLightLevelOmitted(properties.contentLightLevelOmitted());
  }

  private static void setMasteringDisplay(
      MediaFileStreamInfoRecord row, MasteringDisplayColorVolume display) {
    row.setMasteringRedX(display.red().x());
    row.setMasteringRedY(display.red().y());
    row.setMasteringGreenX(display.green().x());
    row.setMasteringGreenY(display.green().y());
    row.setMasteringBlueX(display.blue().x());
    row.setMasteringBlueY(display.blue().y());
    row.setMasteringWhitePointX(display.whitePoint().x());
    row.setMasteringWhitePointY(display.whitePoint().y());
    row.setMasteringMaxLuminance(display.maxLuminance());
    row.setMasteringMinLuminance(display.minLuminance());
  }

  private static void setDynamicRange(MediaFileStreamInfoRecord row, VideoDynamicRange range) {
    row.setDynamicRangeCompatibleBase(range.compatibleBase().name());
    row.setHdr10Plus(range.hdr10Plus().name());
    switch (range.dolbyVision()) {
      case DolbyVision.Unspecified _ -> row.setDolbyVision(Presence.UNSPECIFIED.name());
      case DolbyVision.Absent _ -> row.setDolbyVision(Presence.ABSENT.name());
      case DolbyVision.Present(
              var declared,
              var observedRpu,
              var firstRpuHeader,
              var rpuStartsSample) -> {
        row.setDolbyVision(Presence.PRESENT.name());
        row.setDolbyVisionObservedRpu(observedRpu.name());
        declared.ifPresent(configuration -> setDolbyVisionRecord(row, configuration));
        firstRpuHeader.ifPresent(header -> setFirstRpuHeader(row, header));
        if (rpuStartsSample != Presence.UNSPECIFIED) {
          row.setDolbyVisionRpuStartsSample(rpuStartsSample.name());
        }
      }
    }
  }

  private static void setFirstRpuHeader(
      MediaFileStreamInfoRecord row, DolbyVisionRpuHeader header) {
    row.setFirstRpuVdrRpuProfile(header.vdrRpuProfile());
    row.setFirstRpuBaseLayerFullRange(header.baseLayerFullRange());
    row.setFirstRpuEnhancementLayerSpatialResamplingFilter(
        header.enhancementLayerSpatialResamplingFilter());
    row.setFirstRpuDisableResidual(header.disableResidual());
    row.setFirstRpuVdrBitDepth(header.vdrBitDepth());
  }

  private static void setDolbyVisionRecord(
      MediaFileStreamInfoRecord row, DolbyVisionConfiguration configuration) {
    row.setDolbyVisionProfile(nullable(configuration.profile()));
    row.setDolbyVisionLevel(nullable(configuration.level()));
    row.setDolbyVisionBaseLayerCompatibilityId(nullable(configuration.baseLayerCompatibilityId()));
    row.setDolbyVisionBaseLayer(configuration.baseLayer());
    row.setDolbyVisionEnhancementLayer(configuration.enhancementLayer());
    row.setDolbyVisionRpu(configuration.rpu());
  }

  private static Integer nullable(OptionalInt value) {
    return value.isPresent() ? value.getAsInt() : null;
  }
}
