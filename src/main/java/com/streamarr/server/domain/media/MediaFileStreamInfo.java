package com.streamarr.server.domain.media;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
        .build();
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

  private static Integer nullableInt(OptionalInt value) {
    return value.isPresent() ? value.getAsInt() : null;
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
      return this;
    }

    private void dynamicRange(VideoDynamicRange range) {
      dynamicRangeCompatibleBase = range.compatibleBase();
      hdr10Plus = range.hdr10Plus();
      switch (range.dolbyVision()) {
        case DolbyVision.Unspecified _ -> dolbyVision = Presence.UNSPECIFIED;
        case DolbyVision.Absent _ -> dolbyVision = Presence.ABSENT;
        case DolbyVision.Present(var declared, var observedRpu) -> {
          dolbyVision = Presence.PRESENT;
          dolbyVisionObservedRpu = observedRpu;
          declared.ifPresent(this::dolbyVisionRecord);
        }
      }
    }

    private void dolbyVisionRecord(DolbyVisionConfiguration record) {
      dolbyVisionProfile = nullableInt(record.profile());
      dolbyVisionLevel = nullableInt(record.level());
      dolbyVisionBaseLayerCompatibilityId = nullableInt(record.baseLayerCompatibilityId());
      dolbyVisionBaseLayer = record.baseLayer();
      dolbyVisionEnhancementLayer = record.enhancementLayer();
      dolbyVisionRpu = record.rpu();
    }

    private void containerCropping(ContainerCropping crop) {
      cropTop = crop.top();
      cropBottom = crop.bottom();
      cropLeft = crop.left();
      cropRight = crop.right();
    }
  }
}
