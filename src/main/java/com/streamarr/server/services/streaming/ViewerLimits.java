package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Builder;
import lombok.NonNull;

/**
 * The limits that bound a stream session's output apart from the client capability declaration: the
 * picture box of the fixed quality that the viewer picked, and the maximum width, height and
 * bitrate that the client sends (ADR 0050). {@code dynamicRangeChoice} is the viewer's HDR choice,
 * which only a fixed quality takes (ADR 0013).
 */
@Builder
public record ViewerLimits(
    @NonNull VideoQuality quality,
    @NonNull OptionalInt maxWidth,
    @NonNull OptionalInt maxHeight,
    @NonNull OptionalLong maxBitrate,
    @NonNull Optional<DynamicRangeChoice> dynamicRangeChoice) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class ViewerLimitsBuilder {
    private VideoQuality quality = VideoQuality.AUTO;
    private OptionalInt maxWidth = OptionalInt.empty();
    private OptionalInt maxHeight = OptionalInt.empty();
    private OptionalLong maxBitrate = OptionalLong.empty();
    private Optional<DynamicRangeChoice> dynamicRangeChoice = Optional.empty();
  }

  public static ViewerLimits none() {
    return ViewerLimits.builder().build();
  }

  /**
   * The viewer's limits in the options. A missing quality is Auto, and a maximum at or below zero
   * is no maximum. Only a fixed quality keeps the HDR choice: Auto hides the HDR toggle and
   * Original always plays the source's range (ADR 0013).
   */
  public static ViewerLimits of(@NonNull StreamingOptions options) {
    var quality = Optional.ofNullable(options.quality()).orElse(VideoQuality.AUTO);
    var builder =
        ViewerLimits.builder()
            .quality(quality)
            .maxWidth(positive(options.maxWidth()))
            .maxHeight(positive(options.maxHeight()));
    if (QualityTier.ofFixedQuality(quality).isPresent()) {
      builder.dynamicRangeChoice(Optional.ofNullable(options.dynamicRange()));
    }

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

  /** These limits in Auto: the same maximum width, height and bitrate, with no fixed quality. */
  ViewerLimits inAuto() {
    return ViewerLimits.builder()
        .maxWidth(maxWidth)
        .maxHeight(maxHeight)
        .maxBitrate(maxBitrate)
        .build();
  }

  /** The tier of the fixed quality that the viewer picked, or empty for Auto and Original. */
  Optional<QualityTier> fixedTier() {
    return QualityTier.ofFixedQuality(quality);
  }

  /**
   * Whether a copy keeps a range that the HDR choice allows: SDR refuses a copy of a source whose
   * compatible base is PQ or HLG, or that has none (ADR 0013). A copy of an untagged source plays
   * as SDR.
   */
  boolean allowsCopyRangeOf(@NonNull MediaProbe source) {
    if (dynamicRangeChoice.filter(DynamicRangeChoice.SDR::equals).isEmpty()) {
      return true;
    }

    return source
        .videoPlaybackFacts()
        .map(facts -> VideoRange.ofCompatibleBase(facts.dynamicRange().compatibleBase()))
        .filter(range -> range != VideoRange.SDR)
        .isEmpty();
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
   * copy whose video bitrate and container bitrate are both unknown keep the session's audio. The
   * copy's media playlist is the one a stream copy of the source would list.
   */
  public Optional<AudioDecision> audioStepBesideCopyOf(
      @NonNull MediaProbe source, @NonNull AudioSteps steps, @NonNull CopyMediaPlaylist playlist) {
    if (quality == VideoQuality.ORIGINAL
        || maxBitrate.isEmpty()
        || !VideoCopyBandwidth.isKnown(source, playlist)) {
      return Optional.of(steps.sessionAudio());
    }

    return steps.highestFirst().stream()
        .filter(
            step ->
                VideoCopyBandwidth.averageBandwidth(source, step, playlist)
                    <= maxBitrate.getAsLong())
        .findFirst();
  }
}
