package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * The limits that bound a stream session's output apart from the client capability declaration: the
 * picture box of the fixed quality that the viewer picked, and the maximum width, height and
 * bitrate that the client sends (ADR 0050).
 */
@Builder
public record ViewerLimits(
    @NonNull VideoQuality quality,
    @NonNull OptionalInt maxWidth,
    @NonNull OptionalInt maxHeight,
    @NonNull OptionalLong maxBitrate) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class ViewerLimitsBuilder {
    private VideoQuality quality = VideoQuality.AUTO;
    private OptionalInt maxWidth = OptionalInt.empty();
    private OptionalInt maxHeight = OptionalInt.empty();
    private OptionalLong maxBitrate = OptionalLong.empty();
  }

  public static ViewerLimits none() {
    return ViewerLimits.builder().build();
  }

  /**
   * The viewer's limits in the options. A missing quality is Auto, and a maximum at or below zero
   * is no maximum.
   */
  public static ViewerLimits of(@NonNull StreamingOptions options) {
    var builder =
        ViewerLimits.builder()
            .quality(Optional.ofNullable(options.quality()).orElse(VideoQuality.AUTO))
            .maxWidth(positive(options.maxWidth()))
            .maxHeight(positive(options.maxHeight()));
    positive(options.maxBitrate())
        .ifPresent(bitrate -> builder.maxBitrate(OptionalLong.of(bitrate)));
    return builder.build();
  }

  private static OptionalInt positive(Integer maximum) {
    if (maximum == null || maximum <= 0) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(maximum);
  }

  /** The tier of the fixed quality that the viewer picked, or empty for Auto and Original. */
  Optional<QualityTier> fixedTier() {
    return QualityTier.ofFixedQuality(quality);
  }

  /** The largest picture that the maximum width and height allow, as the client gives them. */
  PictureSize maximumPicture() {
    return new PictureSize(maxWidth.orElse(Integer.MAX_VALUE), maxHeight.orElse(Integer.MAX_VALUE));
  }

  /**
   * Whether a copy of the source fits these limits: the displayed picture fits the fixed quality's
   * picture box in either orientation and the maximum width and height as given, and the container
   * bitrate, when the probe knows it, is at or below the maximum bitrate. A copy in Original
   * ignores the limits.
   */
  public boolean allowsCopyOf(@NonNull MediaProbe source) {
    if (quality == VideoQuality.ORIGINAL) {
      return true;
    }

    var picture = source.displayedSize();
    if (fixedTier().filter(tier -> !picture.fitsWithinEitherOrientation(tier.box())).isPresent()) {
      return false;
    }

    if (maxWidth.isPresent() && picture.width() > maxWidth.getAsInt()) {
      return false;
    }

    if (maxHeight.isPresent() && picture.height() > maxHeight.getAsInt()) {
      return false;
    }

    return maxBitrate.isEmpty()
        || source.bitrate() <= 0
        || source.bitrate() <= maxBitrate.getAsLong();
  }
}
