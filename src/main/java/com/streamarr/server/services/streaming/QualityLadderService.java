package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class QualityLadderService {

  private static final List<QualityVariant> STANDARD_TIERS =
      List.of(
          QualityVariant.builder()
              .width(1920)
              .height(1080)
              .videoBitrate(5_000_000L)
              .audioBitrate(128_000L)
              .label("1080p")
              .build(),
          QualityVariant.builder()
              .width(1280)
              .height(720)
              .videoBitrate(3_000_000L)
              .audioBitrate(128_000L)
              .label("720p")
              .build(),
          QualityVariant.builder()
              .width(854)
              .height(480)
              .videoBitrate(1_500_000L)
              .audioBitrate(96_000L)
              .label("480p")
              .build(),
          QualityVariant.builder()
              .width(640)
              .height(360)
              .videoBitrate(800_000L)
              .audioBitrate(64_000L)
              .label("360p")
              .build());

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
      variants.add(tier);
    }

    if (variants.isEmpty()) {
      variants.add(
          QualityVariant.builder()
              .width(source.width())
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

  private Integer resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return options.maxBitrate();
    }
    return null;
  }
}
