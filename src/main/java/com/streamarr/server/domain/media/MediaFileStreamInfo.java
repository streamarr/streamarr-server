package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ChromaticityCoordinates;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.ContentLightLevel;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.MasteringDisplayColorVolume;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFileStreamInfo {

  @EmbeddedId private MediaFileStreamId id;
  private String codecType;
  private String codec;
  private Integer width;
  private Integer height;
  private Double framerate;
  private Integer channels;
  private Long bitrate;
  private String language;
  private boolean isDefault;
  private boolean isForced;

  @Enumerated(EnumType.STRING)
  private CompatibleBase dynamicRangeCompatibleBase;

  @Column(name = "hdr10_plus")
  @Enumerated(EnumType.STRING)
  private Presence hdr10Plus;

  @Enumerated(EnumType.STRING)
  private Presence dolbyVision;

  @Enumerated(EnumType.STRING)
  private Presence dolbyVisionObservedRpu;

  private Integer dolbyVisionProfile;
  private Integer dolbyVisionLevel;
  private Integer dolbyVisionBaseLayerCompatibilityId;
  private Boolean dolbyVisionBaseLayer;
  private Boolean dolbyVisionEnhancementLayer;
  private Boolean dolbyVisionRpu;
  private Integer bitDepth;

  @Enumerated(EnumType.STRING)
  private ChromaSubsampling chromaSubsampling;

  private Integer rotationDegrees;
  private Integer cropTop;
  private Integer cropBottom;
  private Integer cropLeft;
  private Integer cropRight;
  private String codecString;

  @Column(name = "sample_rate_hz")
  private Integer sampleRate;

  private String audioProfile;
  private Long endTimeSeconds;
  private Integer endTimeNanos;

  @Column(name = "mastering_red_x")
  private Integer masteringRedX;

  @Column(name = "mastering_red_y")
  private Integer masteringRedY;

  @Column(name = "mastering_green_x")
  private Integer masteringGreenX;

  @Column(name = "mastering_green_y")
  private Integer masteringGreenY;

  @Column(name = "mastering_blue_x")
  private Integer masteringBlueX;

  @Column(name = "mastering_blue_y")
  private Integer masteringBlueY;

  @Column(name = "mastering_white_point_x")
  private Integer masteringWhitePointX;

  @Column(name = "mastering_white_point_y")
  private Integer masteringWhitePointY;

  private Long masteringMaxLuminance;
  private Long masteringMinLuminance;
  private Integer maxContentLightLevel;
  private Integer maxFrameAverageLightLevel;
  private String title;
  private String handlerName;
  private Boolean hearingImpaired;

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    var that = (MediaFileStreamInfo) other;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  public StreamInfo toStreamInfo() {
    return StreamInfo.builder()
        .index(id.streamIndex())
        .codecType(codecType)
        .codec(Optional.ofNullable(codec))
        .width(optionalInt(width))
        .height(optionalInt(height))
        .framerate(framerate == null ? OptionalDouble.empty() : OptionalDouble.of(framerate))
        .channels(optionalInt(channels))
        .bitrate(bitrate == null ? OptionalLong.empty() : OptionalLong.of(bitrate))
        .language(Optional.ofNullable(language))
        .isDefault(isDefault)
        .isForced(isForced)
        .dynamicRange(dynamicRange())
        .bitDepth(optionalInt(bitDepth))
        .chromaSubsampling(Optional.ofNullable(chromaSubsampling))
        .rotationDegrees(optionalInt(rotationDegrees))
        .containerCropping(containerCropping())
        .codecString(Optional.ofNullable(codecString))
        .sampleRate(optionalInt(sampleRate))
        .audioProfile(Optional.ofNullable(audioProfile))
        .endTime(
            Optional.ofNullable(endTimeSeconds)
                .map(seconds -> Duration.ofSeconds(seconds, endTimeNanos)))
        .masteringDisplay(masteringDisplay())
        .contentLightLevel(
            Optional.ofNullable(maxContentLightLevel)
                .map(maximum -> new ContentLightLevel(maximum, maxFrameAverageLightLevel)))
        .title(Optional.ofNullable(title))
        .handlerName(Optional.ofNullable(handlerName))
        .hearingImpaired(Optional.ofNullable(hearingImpaired))
        .build();
  }

  private Optional<MasteringDisplayColorVolume> masteringDisplay() {
    if (masteringRedX == null) {
      return Optional.empty();
    }

    return Optional.of(
        MasteringDisplayColorVolume.builder()
            .red(new ChromaticityCoordinates(masteringRedX, masteringRedY))
            .green(new ChromaticityCoordinates(masteringGreenX, masteringGreenY))
            .blue(new ChromaticityCoordinates(masteringBlueX, masteringBlueY))
            .whitePoint(new ChromaticityCoordinates(masteringWhitePointX, masteringWhitePointY))
            .maxLuminance(masteringMaxLuminance)
            .minLuminance(masteringMinLuminance)
            .build());
  }

  private Optional<VideoDynamicRange> dynamicRange() {
    if (dynamicRangeCompatibleBase == null) {
      return Optional.empty();
    }

    return Optional.of(
        new VideoDynamicRange(dynamicRangeCompatibleBase, hdr10Plus, dolbyVisionDetails()));
  }

  private DolbyVision dolbyVisionDetails() {
    return switch (dolbyVision) {
      case UNSPECIFIED -> new DolbyVision.Unspecified();
      case ABSENT -> new DolbyVision.Absent();
      case PRESENT -> new DolbyVision.Present(dolbyVisionRecord(), dolbyVisionObservedRpu);
    };
  }

  private Optional<DolbyVisionConfiguration> dolbyVisionRecord() {
    if (dolbyVisionBaseLayer == null) {
      return Optional.empty();
    }

    return Optional.of(
        DolbyVisionConfiguration.builder()
            .profile(optionalInt(dolbyVisionProfile))
            .level(optionalInt(dolbyVisionLevel))
            .baseLayerCompatibilityId(optionalInt(dolbyVisionBaseLayerCompatibilityId))
            .baseLayer(dolbyVisionBaseLayer)
            .enhancementLayer(dolbyVisionEnhancementLayer)
            .rpu(dolbyVisionRpu)
            .build());
  }

  private Optional<ContainerCropping> containerCropping() {
    if (cropTop == null) {
      return Optional.empty();
    }

    return Optional.of(
        ContainerCropping.builder()
            .top(cropTop)
            .bottom(cropBottom)
            .left(cropLeft)
            .right(cropRight)
            .build());
  }

  private static OptionalInt optionalInt(Integer value) {
    return value == null ? OptionalInt.empty() : OptionalInt.of(value);
  }

  public static class MediaFileStreamInfoBuilder {

    public MediaFileStreamInfoBuilder stream(@NonNull UUID mediaFileId, @NonNull StreamInfo info) {
      id = new MediaFileStreamId(mediaFileId, info.index());
      codecType = info.codecType();
      codec = info.codec().orElse(null);
      width = info.width().isPresent() ? info.width().getAsInt() : null;
      height = info.height().isPresent() ? info.height().getAsInt() : null;
      framerate = info.framerate().isPresent() ? info.framerate().getAsDouble() : null;
      channels = info.channels().isPresent() ? info.channels().getAsInt() : null;
      bitrate = info.bitrate().isPresent() ? info.bitrate().getAsLong() : null;
      language = info.language().orElse(null);
      isDefault = info.isDefault();
      isForced = info.isForced();
      info.dynamicRange().ifPresent(this::dynamicRange);
      bitDepth = nullableInt(info.bitDepth());
      chromaSubsampling = info.chromaSubsampling().orElse(null);
      rotationDegrees = nullableInt(info.rotationDegrees());
      info.containerCropping().ifPresent(this::containerCropping);
      codecString = info.codecString().orElse(null);
      sampleRate = nullableInt(info.sampleRate());
      audioProfile = info.audioProfile().orElse(null);
      endTimeSeconds = info.endTime().map(Duration::getSeconds).orElse(null);
      endTimeNanos = info.endTime().map(Duration::getNano).orElse(null);
      info.masteringDisplay().ifPresent(this::masteringDisplay);
      maxContentLightLevel =
          info.contentLightLevel().map(ContentLightLevel::maxContentLightLevel).orElse(null);
      maxFrameAverageLightLevel =
          info.contentLightLevel().map(ContentLightLevel::maxFrameAverageLightLevel).orElse(null);
      title = info.title().orElse(null);
      handlerName = info.handlerName().orElse(null);
      hearingImpaired = info.hearingImpaired().orElse(null);
      return this;
    }

    private void masteringDisplay(MasteringDisplayColorVolume display) {
      masteringRedX = display.red().x();
      masteringRedY = display.red().y();
      masteringGreenX = display.green().x();
      masteringGreenY = display.green().y();
      masteringBlueX = display.blue().x();
      masteringBlueY = display.blue().y();
      masteringWhitePointX = display.whitePoint().x();
      masteringWhitePointY = display.whitePoint().y();
      masteringMaxLuminance = display.maxLuminance();
      masteringMinLuminance = display.minLuminance();
    }

    private void dynamicRange(VideoDynamicRange range) {
      dynamicRangeCompatibleBase = range.compatibleBase();
      hdr10Plus = range.hdr10Plus();
      switch (range.dolbyVision()) {
        case DolbyVision.Unspecified _ -> dolbyVision = Presence.UNSPECIFIED;
        case DolbyVision.Absent _ -> dolbyVision = Presence.ABSENT;
        case DolbyVision.Present(var declared, var observedRpu, _, _) -> {
          dolbyVision = Presence.PRESENT;
          dolbyVisionObservedRpu = observedRpu;
          declared.ifPresent(this::dolbyVisionRecord);
        }
      }
    }

    private void dolbyVisionRecord(DolbyVisionConfiguration configuration) {
      dolbyVisionProfile = nullableInt(configuration.profile());
      dolbyVisionLevel = nullableInt(configuration.level());
      dolbyVisionBaseLayerCompatibilityId = nullableInt(configuration.baseLayerCompatibilityId());
      dolbyVisionBaseLayer = configuration.baseLayer();
      dolbyVisionEnhancementLayer = configuration.enhancementLayer();
      dolbyVisionRpu = configuration.rpu();
    }

    private void containerCropping(ContainerCropping crop) {
      cropTop = crop.top();
      cropBottom = crop.bottom();
      cropLeft = crop.left();
      cropRight = crop.right();
    }

    private static Integer nullableInt(OptionalInt value) {
      return value.isPresent() ? value.getAsInt() : null;
    }
  }
}
