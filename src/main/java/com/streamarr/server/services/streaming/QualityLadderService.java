package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.EncodedPictureSizes;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.EncodedVideoOutput;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoQuality;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import lombok.NonNull;
import org.springframework.stereotype.Service;

@Service
public class QualityLadderService {

  // More than any codec level allows, so fitting caps it at the entry level's highest bitrate.
  private static final long UNCAPPED_BITRATE = Long.MAX_VALUE;

  /**
   * The variants that an encode offers, each fitted to one capability entry (ADR 0049). Auto offers
   * a standard tier when the source is larger than the next smaller tier's box, scaling the source
   * to fit the tier's box without enlarging it, or one variant when the viewer's limits leave no
   * tier. A fixed quality offers only the default variant, within its tier's box; Original has no
   * box. Sizes each variant from the picture that a player shows, after cropping and rotation. A
   * tier that an entry shrinks into a smaller tier's box gives way to that tier. Auto labels each
   * variant by the tier whose box it reaches, or else by its shorter side. Returns no variant when
   * the viewer's limits leave no picture that the worker encodes.
   */
  public List<QualityVariant> generateVariants(@NonNull QualityLadderRequest request) {
    var limits = request.viewerLimits();
    var source = request.probe().displayedSize();
    var viewerBounds = limits.maximumPicture();
    var maxBitrate = limits.maxBitrate();
    if (limits.quality() != VideoQuality.AUTO) {
      var bounds =
          limits
              .fixedTier()
              .map(tier -> viewerBounds.boundedBy(tier.box().orientedLike(source)))
              .orElse(viewerBounds);
      return fitUntiered(request, bounds, maxBitrate)
          .map(QualityLadderService::defaultVariant)
          .stream()
          .toList();
    }

    var variants = new ArrayList<QualityVariant>();
    for (var tier : QualityTier.offeredFor(source)) {
      if (maxBitrate.isPresent()
          && tier.videoBitrate() + request.audioBitrate() > maxBitrate.getAsLong()) {
        continue;
      }

      var box = tier.box().orientedLike(source);
      if (!fitsViewerBounds(source, box, viewerBounds)) {
        continue;
      }

      fit(request, box, tier.videoBitrate())
          .filter(output -> QualityTier.containing(pictureOf(output)) == tier)
          .map(output -> labelledVariant(output, source))
          .ifPresent(variants::add);
    }

    if (variants.isEmpty()) {
      return fitUntiered(request, viewerBounds, maxBitrate)
          .map(output -> labelledVariant(output, source))
          .stream()
          .toList();
    }

    return withoutRepeatedLabels(variants, source);
  }

  // A variant takes the label of the tier whose box it reaches, or else its shorter side's
  // (ADR 0050).
  private static QualityVariant labelledVariant(EncodedVideoOutput output, PictureSize source) {
    var picture = pictureOf(output);
    var tier = QualityTier.containing(picture);
    if (EncodedPictureSizes.reaches(source, picture, tier.box())) {
      return variantBuilder(output).label(tier.label()).build();
    }

    return variantBuilder(output).label(Math.min(picture.width(), picture.height()) + "p").build();
  }

  // A label names a variant's playlist, so a variant that only its shorter side labels gives way
  // to the variant whose tier has the same label.
  private static List<QualityVariant> withoutRepeatedLabels(
      List<QualityVariant> variants, PictureSize source) {
    var tierLabels =
        variants.stream()
            .filter(variant -> reachesItsTier(variant, source))
            .map(QualityVariant::label)
            .collect(Collectors.toSet());
    return variants.stream()
        .filter(variant -> reachesItsTier(variant, source) || !tierLabels.contains(variant.label()))
        .toList();
  }

  private static boolean reachesItsTier(QualityVariant variant, PictureSize source) {
    var picture = new PictureSize(variant.width(), variant.height());
    return EncodedPictureSizes.reaches(source, picture, QualityTier.containing(picture).box());
  }

  // The viewer's limits bound the tier's picture before any entry shrinks it.
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

  // The untiered variant asks for the source's bitrate within its video budget. A probe that
  // names no source bitrate reports 0, so the variant then asks for the nominal bitrate of the
  // smallest tier whose box holds the fitted picture, within the budget. The entry's codec level
  // caps each.
  private static Optional<EncodedVideoOutput> fitUntiered(
      QualityLadderRequest request, PictureSize bounds, OptionalLong maxBitrate) {
    var budget = videoBudget(request, bounds, maxBitrate);
    var sourceBitrate = sourceBitrate(request);
    if (sourceBitrate.isPresent()) {
      return fit(request, budget.bounds(), Math.min(sourceBitrate.getAsLong(), budget.bitrate()));
    }

    return fit(request, budget.bounds(), budget.bitrate())
        .map(QualityLadderService::atNominalBitrate);
  }

  private record VideoBudget(PictureSize bounds, long bitrate) {}

  // The viewer's maximum bitrate covers the video and the stream session's audio. When less than
  // the 360p tier's video bitrate remains, the variant takes the 360p box and the 360p tier's
  // bitrate within the maximum, because an encoder reads a zero bitrate as no limit (ADR 0050).
  private static VideoBudget videoBudget(
      QualityLadderRequest request, PictureSize bounds, OptionalLong maxBitrate) {
    if (maxBitrate.isEmpty()) {
      return new VideoBudget(bounds, UNCAPPED_BITRATE);
    }

    var remainder = maxBitrate.getAsLong() - request.audioBitrate();
    var smallest = QualityTier.TIER_360P;
    if (remainder >= smallest.videoBitrate()) {
      return new VideoBudget(bounds, remainder);
    }

    var box = smallest.box().orientedLike(request.probe().displayedSize());
    return new VideoBudget(
        bounds.boundedBy(box), Math.min(smallest.videoBitrate(), maxBitrate.getAsLong()));
  }

  private static OptionalLong sourceBitrate(QualityLadderRequest request) {
    var bitrate = request.probe().bitrate();
    if (bitrate <= 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(bitrate);
  }

  private static EncodedVideoOutput atNominalBitrate(EncodedVideoOutput output) {
    var nominalBitrate = QualityTier.containing(pictureOf(output)).videoBitrate();
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

  private static QualityVariant defaultVariant(EncodedVideoOutput output) {
    return variantBuilder(output).label(StreamSession.defaultVariant()).build();
  }

  private static QualityVariant.QualityVariantBuilder variantBuilder(EncodedVideoOutput output) {
    return QualityVariant.builder()
        .width(output.width())
        .height(output.height())
        .videoBitrate(output.bitrate())
        .frameRate(output.frameRate());
  }
}
