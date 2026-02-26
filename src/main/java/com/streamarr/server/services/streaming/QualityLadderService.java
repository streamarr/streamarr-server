package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamingOptions;
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

  public List<QualityVariant> generateVariants(MediaProbe source, StreamingOptions options) {
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
              .videoBitrate(source.bitrate())
              .audioBitrate(64_000L)
              .label(source.height() + "p")
              .build());
    }

    return List.copyOf(variants);
  }

  private int resolveMaxHeight(MediaProbe source, StreamingOptions options) {
    if (options.maxHeight() != null && options.maxHeight() > 0) {
      return Math.min(options.maxHeight(), source.height());
    }

    return source.height();
  }

  private int computeWidthForHeight(MediaProbe source, int targetHeight) {
    int width = (int) Math.round((double) source.width() / source.height() * targetHeight);
    if (width % 2 != 0) {
      width++;
    }
    return width;
  }

  private Integer resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return options.maxBitrate();
    }

    return null;
  }
}
