package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class QualityLadderService {

  private record QualityTier(int height, long videoBitrate, long audioBitrate, String label) {}

  private static final List<QualityTier> STANDARD_TIERS =
      List.of(
          new QualityTier(1080, 5_000_000L, 128_000L, "1080p"),
          new QualityTier(720, 3_000_000L, 128_000L, "720p"),
          new QualityTier(480, 1_500_000L, 96_000L, "480p"),
          new QualityTier(360, 800_000L, 64_000L, "360p"));

  /** Sizes each variant from the picture that a player shows, after cropping and rotation. */
  public List<QualityVariant> generateVariants(MediaProbe probe, StreamingOptions options) {
    var source = probe.displayedSize();
    var maxHeight = resolveMaxHeight(source, options);
    var maxBitrate = resolveMaxBitrate(options);

    var variants = new ArrayList<QualityVariant>();
    for (var tier : STANDARD_TIERS) {
      if (tier.height() > source.height()) {
        continue;
      }

      if (tier.height() > maxHeight) {
        continue;
      }

      if (maxBitrate != null && tier.videoBitrate() > maxBitrate) {
        continue;
      }

      var width = computeWidthForHeight(source, tier.height());

      variants.add(
          QualityVariant.builder()
              .width(width)
              .height(tier.height())
              .videoBitrate(tier.videoBitrate())
              .audioBitrate(tier.audioBitrate())
              .label(tier.label())
              .build());
    }

    if (variants.isEmpty()) {
      variants.add(
          QualityVariant.builder()
              .width(computeWidthForHeight(source, source.height()))
              .height(source.height())
              .videoBitrate(probe.bitrate())
              .audioBitrate(64_000L)
              .label(source.height() + "p")
              .build());
    }

    return List.copyOf(variants);
  }

  private int resolveMaxHeight(PictureSize source, StreamingOptions options) {
    if (options.maxHeight() != null && options.maxHeight() > 0) {
      return Math.min(options.maxHeight(), source.height());
    }

    return source.height();
  }

  // The worker scales with scale=-2:<height>. FFmpeg computes that width as
  // round(height * width / (2 * height)) * 2, rounding half away from zero.
  private static int computeWidthForHeight(PictureSize source, int targetHeight) {
    var halfWidth =
        BigDecimal.valueOf((long) targetHeight * source.width())
            .divide(BigDecimal.valueOf(2L * source.height()), 0, RoundingMode.HALF_UP);
    return halfWidth.intValueExact() * 2;
  }

  private Integer resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return options.maxBitrate();
    }

    return null;
  }
}
