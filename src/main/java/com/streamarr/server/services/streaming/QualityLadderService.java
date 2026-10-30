package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.EncodedVideoOutput;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

  private static final long SOURCE_SIZED_AUDIO_BITRATE = 64_000L;

  /**
   * The variants that an encode offers, each fitted to one capability entry (ADR 0049). Auto offers
   * the standard tiers, or one variant when the source or the viewer's options leave no tier. A
   * fixed quality offers only the default variant. Sizes each variant from the picture that a
   * player shows, after cropping and rotation. A tier that an entry shrinks to the size of a
   * smaller tier gives way to that tier.
   */
  public List<QualityVariant> generateVariants(@NonNull QualityLadderRequest request) {
    var options = request.options();
    var source = request.probe().displayedSize();
    var viewerBounds = viewerBounds(source, options);
    if (!isAutoQuality(options)) {
      var output = fit(request, viewerBounds, request.probe().bitrate());
      return List.of(sourceSizedVariant(output, StreamSession.defaultVariant()));
    }

    var maxBitrate = resolveMaxBitrate(options);
    var variants = new LinkedHashMap<String, QualityVariant>();
    for (var tier : STANDARD_TIERS) {
      if (tier.height() > viewerBounds.height()) {
        continue;
      }

      if (maxBitrate != null && tier.videoBitrate() > maxBitrate) {
        continue;
      }

      if (EncodedPictureSizes.atHeight(source, tier.height()).width() > viewerBounds.width()) {
        continue;
      }

      var bounds = new PictureSize(viewerBounds.width(), tier.height());
      var variant = tierVariant(fit(request, bounds, tier.videoBitrate()), tier);
      variants.put(variant.label(), variant);
    }

    if (variants.isEmpty()) {
      var output = fit(request, viewerBounds, request.probe().bitrate());
      return List.of(sourceSizedVariant(output, output.height() + "p"));
    }

    return List.copyOf(new ArrayList<>(variants.values()));
  }

  // The decision encodes only a codec family that one entry fits at the source's size, so one
  // entry fits every smaller picture of the source.
  private static EncodedVideoOutput fit(
      QualityLadderRequest request, PictureSize bounds, long bitrate) {
    var variantRequest =
        EncodedVariantRequest.builder()
            .codecProfile(request.codecProfile())
            .source(request.probe().displayedSize())
            .bounds(bounds)
            .frameRate(request.frameRate())
            .bitrate(bitrate)
            .build();
    return request
        .capabilityDeclaration()
        .fitHlsEncode(variantRequest)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No capability entry fits the encode that the transcode decision chose"));
  }

  private static QualityVariant tierVariant(EncodedVideoOutput output, QualityTier tier) {
    return variantBuilder(output)
        .audioBitrate(tier.audioBitrate())
        .label(output.height() + "p")
        .build();
  }

  private static QualityVariant sourceSizedVariant(EncodedVideoOutput output, String label) {
    return variantBuilder(output).audioBitrate(SOURCE_SIZED_AUDIO_BITRATE).label(label).build();
  }

  private static QualityVariant.QualityVariantBuilder variantBuilder(EncodedVideoOutput output) {
    return QualityVariant.builder()
        .width(output.width())
        .height(output.height())
        .videoBitrate(output.bitrate())
        .frameRate(output.frameRate());
  }

  private static boolean isAutoQuality(StreamingOptions options) {
    return options.quality() == null || options.quality() == VideoQuality.AUTO;
  }

  // The largest picture that the viewer's options allow, never larger than the source.
  private static PictureSize viewerBounds(PictureSize source, StreamingOptions options) {
    var maxWidth = Integer.MAX_VALUE;
    if (options.maxWidth() != null && options.maxWidth() > 0) {
      maxWidth = options.maxWidth();
    }

    var maxHeight = source.height();
    if (options.maxHeight() != null && options.maxHeight() > 0) {
      maxHeight = Math.min(options.maxHeight(), source.height());
    }

    return new PictureSize(maxWidth, maxHeight);
  }

  private Integer resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return options.maxBitrate();
    }

    return null;
  }
}
