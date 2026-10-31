package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioSteps;
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

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
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
   * Whether a copy's picture fits these limits: the displayed picture fits the fixed quality's
   * picture box in either orientation and the maximum width and height as given. A copy in Original
   * ignores the limits.
   */
  public boolean allowsCopyPictureOf(@NonNull MediaProbe source) {
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

    return maxHeight.isEmpty() || picture.height() <= maxHeight.getAsInt();
  }

  /**
   * The first audio step beside which a copy of the source costs at most the maximum bitrate, or
   * empty when none does (ADR 0050). A copy in Original, a copy without a maximum bitrate and a
   * copy whose video bitrate and container bitrate are both unknown keep the session's audio.
   */
  public Optional<AudioDecision> audioStepBesideCopyOf(
      @NonNull MediaProbe source, @NonNull AudioSteps steps) {
    if (quality == VideoQuality.ORIGINAL
        || maxBitrate.isEmpty()
        || !VideoCopyBandwidth.isKnown(source)) {
      return Optional.of(steps.sessionAudio());
    }

    return steps.highestFirst().stream()
        .filter(step -> VideoCopyBandwidth.averageBandwidth(source, step) <= maxBitrate.getAsLong())
        .findFirst();
  }
}
