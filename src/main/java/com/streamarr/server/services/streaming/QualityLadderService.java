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
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;
import org.springframework.stereotype.Service;

@Service
public class QualityLadderService {

  // A tier's box bounds its variant in either orientation.
  private record QualityTier(String label, PictureSize box, long videoBitrate, long audioBitrate) {}

  // The 2160p and 1440p bitrates are the lowest 3840x2160 and the 2560x1440 rates in Apple's HLS
  // Authoring Specification for Apple Devices, item 1.25.
  private static final QualityTier TIER_2160P =
      new QualityTier("2160p", new PictureSize(3840, 2160), 11_600_000L, 128_000L);
  private static final QualityTier TIER_1440P =
      new QualityTier("1440p", new PictureSize(2560, 1440), 8_100_000L, 128_000L);
  private static final QualityTier TIER_1080P =
      new QualityTier("1080p", new PictureSize(1920, 1080), 5_000_000L, 128_000L);
  private static final QualityTier TIER_720P =
      new QualityTier("720p", new PictureSize(1280, 720), 3_000_000L, 128_000L);
  private static final QualityTier TIER_480P =
      new QualityTier("480p", new PictureSize(854, 480), 1_500_000L, 96_000L);
  private static final QualityTier TIER_360P =
      new QualityTier("360p", new PictureSize(640, 360), 800_000L, 64_000L);

  private static final List<QualityTier> STANDARD_TIERS =
      List.of(TIER_2160P, TIER_1440P, TIER_1080P, TIER_720P, TIER_480P, TIER_360P);

  private static final long VIEWER_AND_ENTRY_BOUNDED_AUDIO_BITRATE = 64_000L;
  // More than any codec level allows, so fitting caps it at the entry level's highest bitrate.
  private static final long UNCAPPED_BITRATE = Long.MAX_VALUE;

  /**
   * The variants that an encode offers, each fitted to one capability entry (ADR 0049). Auto offers
   * a standard tier when the source is larger than the next smaller tier's box, scaling the source
   * to fit the tier's box without enlarging it, or one variant when the viewer's options leave no
   * tier. A fixed quality offers only the default variant, within its tier's box; Original has no
   * box. Sizes each variant from the picture that a player shows, after cropping and rotation. A
   * tier that an entry shrinks into a smaller tier's box gives way to that tier. Returns no variant
   * when the viewer's options leave no picture that the worker encodes.
   */
  public List<QualityVariant> generateVariants(@NonNull QualityLadderRequest request) {
    var options = request.options();
    var source = request.probe().displayedSize();
    var viewerBounds = viewerBounds(source, options);
    var maxBitrate = resolveMaxBitrate(options);
    if (!isAutoQuality(options)) {
      var bounds =
          fixedQualityTier(options.quality())
              .map(tier -> within(viewerBounds, tier.box().orientedLike(source)))
              .orElse(viewerBounds);
      return fitUntiered(request, bounds, maxBitrate)
          .map(output -> viewerAndEntryBoundedVariant(output, StreamSession.defaultVariant()))
          .stream()
          .toList();
    }

    var variants = new ArrayList<QualityVariant>();
    for (var tier : tiersOffered(source)) {
      if (maxBitrate.isPresent() && tier.videoBitrate() > maxBitrate.getAsLong()) {
        continue;
      }

      var box = tier.box().orientedLike(source);
      if (!fitsViewerBounds(source, box, viewerBounds)) {
        continue;
      }

      fit(request, box, tier.videoBitrate())
          .filter(output -> tierContaining(pictureOf(output)) == tier)
          .map(output -> tierVariant(output, tier))
          .ifPresent(variants::add);
    }

    if (variants.isEmpty()) {
      return fitUntiered(request, viewerBounds, maxBitrate)
          .map(output -> viewerAndEntryBoundedVariant(output, output.height() + "p"))
          .stream()
          .toList();
    }

    return List.copyOf(variants);
  }

  private static Optional<QualityTier> fixedQualityTier(VideoQuality quality) {
    return switch (quality) {
      case LOW_360P -> Optional.of(TIER_360P);
      case MEDIUM_480P -> Optional.of(TIER_480P);
      case HIGH_720P -> Optional.of(TIER_720P);
      case FULL_HD_1080P -> Optional.of(TIER_1080P);
      case UHD_4K -> Optional.of(TIER_2160P);
      case AUTO, ORIGINAL -> Optional.empty();
    };
  }

  private static PictureSize within(PictureSize bounds, PictureSize box) {
    return new PictureSize(
        Math.min(bounds.width(), box.width()), Math.min(bounds.height(), box.height()));
  }

  // The tier whose box holds the source and every smaller tier. The smallest tier has no smaller
  // box, so every source is offered it.
  private static List<QualityTier> tiersOffered(PictureSize source) {
    var sourceTier = STANDARD_TIERS.indexOf(tierContaining(source));
    return STANDARD_TIERS.subList(sourceTier, STANDARD_TIERS.size());
  }

  // The smallest tier whose box holds the picture, or the largest tier when none does.
  private static QualityTier tierContaining(PictureSize picture) {
    return STANDARD_TIERS.reversed().stream()
        .filter(tier -> picture.fitsWithinEitherOrientation(tier.box()))
        .findFirst()
        .orElse(STANDARD_TIERS.getFirst());
  }

  // The viewer's options bound the tier's picture before any entry shrinks it.
  private static boolean fitsViewerBounds(
      PictureSize source, PictureSize box, PictureSize viewerBounds) {
    return EncodedPictureSizes.picturesWithin(source, box)
        .findFirst()
        .filter(
            picture ->
                picture.width() <= viewerBounds.width()
                    && picture.height() <= viewerBounds.height())
        .isPresent();
  }

  private static PictureSize pictureOf(EncodedVideoOutput output) {
    return new PictureSize(output.width(), output.height());
  }

  // The untiered variant asks for the source's bitrate within the viewer's maximum. A probe that
  // names no source bitrate reports 0, so the variant then asks for the nominal bitrate of the
  // smallest tier whose box holds the fitted picture, within the viewer's maximum. The entry's
  // codec level caps each.
  private static Optional<EncodedVideoOutput> fitUntiered(
      QualityLadderRequest request, PictureSize bounds, OptionalLong maxBitrate) {
    var bitrateLimit = maxBitrate.orElse(UNCAPPED_BITRATE);
    var sourceBitrate = sourceBitrate(request);
    if (sourceBitrate.isPresent()) {
      return fit(request, bounds, Math.min(sourceBitrate.getAsLong(), bitrateLimit));
    }

    return fit(request, bounds, bitrateLimit).map(QualityLadderService::atNominalBitrate);
  }

  private static OptionalLong sourceBitrate(QualityLadderRequest request) {
    var bitrate = request.probe().bitrate();
    if (bitrate <= 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(bitrate);
  }

  private static EncodedVideoOutput atNominalBitrate(EncodedVideoOutput output) {
    var nominalBitrate = tierContaining(pictureOf(output)).videoBitrate();
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
    return variantBuilder(output).audioBitrate(tier.audioBitrate()).label(tier.label()).build();
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
