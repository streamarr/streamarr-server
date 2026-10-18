package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
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

      var size = EncodedPictureSizes.atHeight(source, tier.height());

      variants.add(
          QualityVariant.builder()
              .width(size.width())
              .height(size.height())
              .videoBitrate(tier.videoBitrate())
              .audioBitrate(tier.audioBitrate())
              .label(tier.label())
              .build());
    }

    if (variants.isEmpty()) {
      var size = EncodedPictureSizes.atHeight(source, source.height());
      variants.add(
          QualityVariant.builder()
              .width(size.width())
              .height(size.height())
              .videoBitrate(probe.bitrate())
              .audioBitrate(64_000L)
              .label(size.height() + "p")
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

  private Integer resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return options.maxBitrate();
    }

    return null;
  }
}
