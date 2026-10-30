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
    @NonNull Optional<PictureSize> pictureBox,
    @NonNull OptionalInt maxWidth,
    @NonNull OptionalInt maxHeight,
    @NonNull OptionalLong maxBitrate) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class ViewerLimitsBuilder {
    private Optional<PictureSize> pictureBox = Optional.empty();
    private OptionalInt maxWidth = OptionalInt.empty();
    private OptionalInt maxHeight = OptionalInt.empty();
    private OptionalLong maxBitrate = OptionalLong.empty();
  }

  public static ViewerLimits none() {
    return ViewerLimits.builder().build();
  }

  /**
   * The viewer's limits in the options. Original ignores them, and a maximum at or below zero is no
   * maximum.
   */
  public static ViewerLimits of(@NonNull StreamingOptions options) {
    var quality = Optional.ofNullable(options.quality()).orElse(VideoQuality.AUTO);
    if (quality == VideoQuality.ORIGINAL) {
      return none();
    }

    var builder =
        ViewerLimits.builder()
            .pictureBox(QualityTier.ofFixedQuality(quality).map(QualityTier::box))
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

  /**
   * Whether a copy of the source fits these limits: the displayed picture fits the picture box in
   * either orientation and the maximum width and height as given, and the container bitrate, when
   * the probe knows it, is at or below the maximum bitrate.
   */
  public boolean allowsCopyOf(@NonNull MediaProbe source) {
    var picture = source.displayedSize();
    if (pictureBox.filter(box -> !picture.fitsWithinEitherOrientation(box)).isPresent()) {
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
