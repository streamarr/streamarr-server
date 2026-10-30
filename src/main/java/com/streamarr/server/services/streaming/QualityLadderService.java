package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
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

  /**
   * The variants that an encode offers. Auto offers the standard tiers, or one variant when the
   * source or the viewer's options leave no tier. A fixed quality offers only the default variant.
   * Sizes each variant from the picture that a player shows, after cropping and rotation.
   */
  public List<QualityVariant> generateVariants(@NonNull QualityLadderRequest request) {
    var options = request.options();
    var source = request.probe().displayedSize();
    var maxHeight = resolveMaxHeight(source, options);
    if (!isAutoQuality(options)) {
      return List.of(sourceSizedVariant(request, maxHeight, StreamSession.defaultVariant()));
    }

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
              .frameRate(request.frameRate())
              .build());
    }

    if (variants.isEmpty()) {
      var height = EncodedPictureSizes.atHeight(source, maxHeight).height();
      variants.add(sourceSizedVariant(request, maxHeight, height + "p"));
    }

    return List.copyOf(variants);
  }

  // The variant keeps the source's bitrate at the largest height that the viewer allows.
  private static QualityVariant sourceSizedVariant(
      QualityLadderRequest request, int maxHeight, String label) {
    var probe = request.probe();
    var size = EncodedPictureSizes.atHeight(probe.displayedSize(), maxHeight);
    return QualityVariant.builder()
        .width(size.width())
        .height(size.height())
        .videoBitrate(probe.bitrate())
        .audioBitrate(64_000L)
        .label(label)
        .frameRate(request.frameRate())
        .build();
  }

  private static boolean isAutoQuality(StreamingOptions options) {
    return options.quality() == null || options.quality() == VideoQuality.AUTO;
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
