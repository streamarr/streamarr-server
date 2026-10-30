package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.EncodedVideoOutput;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;
import org.springframework.stereotype.Service;

@Service
public class QualityLadderService {

  private record QualityTier(int height, long videoBitrate, long audioBitrate) {}

  private static final List<QualityTier> STANDARD_TIERS =
      List.of(
          new QualityTier(1080, 5_000_000L, 128_000L),
          new QualityTier(720, 3_000_000L, 128_000L),
          new QualityTier(480, 1_500_000L, 96_000L),
          new QualityTier(360, 800_000L, 64_000L));

  private static final long VIEWER_AND_ENTRY_BOUNDED_AUDIO_BITRATE = 64_000L;
  // More than any codec level allows, so fitting caps it at the entry level's highest bitrate.
  private static final long UNCAPPED_BITRATE = Long.MAX_VALUE;

  /**
   * The variants that an encode offers, each fitted to one capability entry (ADR 0049). Auto offers
   * the standard tiers, or one variant when the source or the viewer's options leave no tier. A
   * fixed quality offers only the default variant. Sizes each variant from the picture that a
   * player shows, after cropping and rotation. A tier that an entry shrinks to the size of a
   * smaller tier gives way to that tier. Returns no variant when the viewer's options leave no
   * picture that the worker encodes.
   */
  public List<QualityVariant> generateVariants(@NonNull QualityLadderRequest request) {
    var options = request.options();
    var source = request.probe().displayedSize();
    var viewerBounds = viewerBounds(source, options);
    var maxBitrate = resolveMaxBitrate(options);
    if (!isAutoQuality(options)) {
      return fitUntiered(request, viewerBounds, maxBitrate)
          .map(output -> viewerAndEntryBoundedVariant(output, StreamSession.defaultVariant()))
          .stream()
          .toList();
    }

    var variants = new LinkedHashMap<String, QualityVariant>();
    for (var tier : STANDARD_TIERS) {
      if (tier.height() > viewerBounds.height()) {
        continue;
      }

      if (maxBitrate.isPresent() && tier.videoBitrate() > maxBitrate.getAsLong()) {
        continue;
      }

      if (EncodedPictureSizes.atHeight(source, tier.height()).width() > viewerBounds.width()) {
        continue;
      }

      var bounds = new PictureSize(viewerBounds.width(), tier.height());
      fit(request, bounds, tier.videoBitrate())
          .map(output -> tierVariant(output, tier))
          .ifPresent(variant -> variants.put(variant.label(), variant));
    }

    if (variants.isEmpty()) {
      return fitUntiered(request, viewerBounds, maxBitrate)
          .map(output -> viewerAndEntryBoundedVariant(output, output.height() + "p"))
          .stream()
          .toList();
    }

    return List.copyOf(variants.values());
  }

  // The untiered variant asks for the source's bitrate within the viewer's maximum. A probe that
  // names no source bitrate reports 0, so the variant then asks for the viewer's maximum, or else
  // for the nominal bitrate of the tier at the fitted height. The entry's codec level caps each.
  private static Optional<EncodedVideoOutput> fitUntiered(
      QualityLadderRequest request, PictureSize bounds, OptionalLong maxBitrate) {
    var requestedBitrate = requestedUntieredBitrate(sourceBitrate(request), maxBitrate);
    if (requestedBitrate.isPresent()) {
      return fit(request, bounds, requestedBitrate.getAsLong());
    }

    return fit(request, bounds, UNCAPPED_BITRATE).map(QualityLadderService::atNominalBitrate);
  }

  private static OptionalLong sourceBitrate(QualityLadderRequest request) {
    var bitrate = request.probe().bitrate();
    if (bitrate <= 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(bitrate);
  }

  private static OptionalLong requestedUntieredBitrate(
      OptionalLong sourceBitrate, OptionalLong maxBitrate) {
    if (sourceBitrate.isEmpty()) {
      return maxBitrate;
    }

    if (maxBitrate.isEmpty()) {
      return sourceBitrate;
    }

    return OptionalLong.of(Math.min(sourceBitrate.getAsLong(), maxBitrate.getAsLong()));
  }

  // An output fitted at UNCAPPED_BITRATE carries the highest bitrate that its entry's level allows.
  private static EncodedVideoOutput atNominalBitrate(EncodedVideoOutput output) {
    var nominalBitrate =
        STANDARD_TIERS.reversed().stream()
            .filter(tier -> tier.height() >= output.height())
            .findFirst()
            .orElse(STANDARD_TIERS.getFirst())
            .videoBitrate();
    return output.toBuilder().bitrate(Math.min(nominalBitrate, output.bitrate())).build();
  }

  // The decision encodes only a codec family that one entry fits at the source's size, so one
  // entry fits every smaller picture of the source that the worker encodes. Only bounds below the
  // smallest such picture leave none.
  private static Optional<EncodedVideoOutput> fit(
      QualityLadderRequest request, PictureSize bounds, long bitrate) {
    var variantRequest =
        EncodedVariantRequest.builder()
            .codecProfile(request.codecProfile())
            .source(request.probe().displayedSize())
            .bounds(bounds)
            .frameRate(request.frameRate())
            .bitrate(bitrate)
            .build();
    return request.capabilityDeclaration().fitHlsEncode(variantRequest);
  }

  private static QualityVariant tierVariant(EncodedVideoOutput output, QualityTier tier) {
    return variantBuilder(output)
        .audioBitrate(tier.audioBitrate())
        .label(output.height() + "p")
        .build();
  }

  private static QualityVariant viewerAndEntryBoundedVariant(
      EncodedVideoOutput output, String label) {
    return variantBuilder(output)
        .audioBitrate(VIEWER_AND_ENTRY_BOUNDED_AUDIO_BITRATE)
        .label(label)
        .build();
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

  private static OptionalLong resolveMaxBitrate(StreamingOptions options) {
    if (options.maxBitrate() != null && options.maxBitrate() > 0) {
      return OptionalLong.of(options.maxBitrate());
    }

    return OptionalLong.empty();
  }
}
