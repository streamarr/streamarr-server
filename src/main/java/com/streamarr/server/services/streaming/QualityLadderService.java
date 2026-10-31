package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
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
  // The 416x234 row of the H.264 ladder in Apple's HLS Authoring Specification for Apple Devices.
  private static final long LOWEST_VIDEO_BITRATE = 145_000L;
  private static final int STEREO = 2;

  /**
   * The variants that an encode offers, each fitted to one capability entry (ADR 0049), and the
   * audio step that every variant's cost includes (ADR 0050). When the session's audio leaves less
   * than the 360p tier's video bitrate under the maximum bitrate, the ladder takes the first audio
   * step that leaves it. A lower multichannel step is taken only when it lets a larger picture fit.
   * Auto offers a standard tier when the source is larger than the next smaller tier's box, scaling
   * the source to fit the tier's box without enlarging it, or one variant when the viewer's limits
   * leave no tier. A fixed quality offers only the default variant, within its tier's box and at
   * most the nominal bitrate of the smallest tier whose box holds the encoded picture; Original has
   * no box. Sizes each variant from the picture that a player shows, after cropping and rotation. A
   * tier that an entry shrinks into a smaller tier's box gives way to that tier. Auto labels each
   * variant by the tier whose box it reaches, or else by its shorter side. Offers no variant when
   * the viewer's limits leave no picture that the worker encodes.
   */
  public QualityLadder generateVariants(@NonNull QualityLadderRequest request) {
    var audio = audioForTheLargestPicture(request, audioLeavingTheSmallestTier(request));
    return new QualityLadder(variants(Sizing.within(request, audio)), audio);
  }

  // The first audio step that leaves the 360p tier's video bitrate under the maximum bitrate, or
  // the lowest step when none does.
  private static AudioDecision audioLeavingTheSmallestTier(QualityLadderRequest request) {
    var steps = request.audioSteps();
    var maxBitrate = request.viewerLimits().maxBitrate();
    if (maxBitrate.isEmpty()) {
      return steps.sessionAudio();
    }

    var smallestTierBitrate = QualityTier.TIER_360P.videoBitrate(request.codecProfile());
    return steps.highestFirst().stream()
        .filter(step -> maxBitrate.getAsLong() - step.bitrate() >= smallestTierBitrate)
        .findFirst()
        .orElse(steps.lowest());
  }

  // The multichannel steps below the chosen step, from the highest, lead to the first step whose
  // ladder reaches the largest picture that any of them reaches. A stereo step is never taken for a
  // larger picture, and the steps list every multichannel step before the stereo ones.
  private static AudioDecision audioForTheLargestPicture(
      QualityLadderRequest request, AudioDecision chosen) {
    if (request.viewerLimits().maxBitrate().isEmpty()) {
      return chosen;
    }

    var steps = request.audioSteps().highestFirst();
    var largest = chosen;
    var largestPicture = pictureReached(request, chosen);
    var lowerMultichannelSteps =
        steps.subList(steps.indexOf(chosen) + 1, steps.size()).stream()
            .takeWhile(step -> step.channels() > STEREO)
            .toList();
    for (var step : lowerMultichannelSteps) {
      var picture = pictureReached(request, step);
      if (picture > largestPicture) {
        largest = step;
        largestPicture = picture;
      }
    }

    return largest;
  }

  // How large a picture the ladder reaches beside the audio. In Auto, the area of its largest
  // variant, so a higher top tier reaches more. A single variant reaches more only when it is the
  // variant that it would be without a maximum bitrate, at its full bitrate.
  private static long pictureReached(QualityLadderRequest request, AudioDecision audio) {
    var variants = variants(Sizing.within(request, audio));
    if (request.viewerLimits().quality() == VideoQuality.AUTO) {
      return variants.stream()
          .mapToLong(variant -> (long) variant.width() * variant.height())
          .max()
          .orElse(0);
    }

    return variants.equals(variants(Sizing.withoutMaximumBitrate(request))) ? 1 : 0;
  }

  // A ladder request with the maximum bitrate that bounds its variants and the bitrate of the audio
  // step that every variant's cost includes.
  private record Sizing(QualityLadderRequest request, OptionalLong maxBitrate, long audioBitrate) {

    static Sizing within(QualityLadderRequest request, AudioDecision audio) {
      return new Sizing(request, request.viewerLimits().maxBitrate(), audio.bitrate());
    }

    static Sizing withoutMaximumBitrate(QualityLadderRequest request) {
      return new Sizing(request, OptionalLong.empty(), 0);
    }
  }

  private static List<QualityVariant> variants(Sizing sizing) {
    var request = sizing.request();
    var limits = request.viewerLimits();
    var source = request.probe().displayedSize();
    var viewerBounds = limits.maximumPicture();
    var maxBitrate = sizing.maxBitrate();
    if (limits.quality() != VideoQuality.AUTO) {
      return fitDefaultVariant(sizing).map(QualityLadderService::defaultVariant).stream().toList();
    }

    var variants = new ArrayList<LabelledVariant>();
    for (var tier : QualityTier.offeredFor(source)) {
      var tierBitrate = tier.videoBitrate(request.codecProfile());
      var box = tier.box().orientedLike(source);
      if (exceeds(maxBitrate, tierBitrate + sizing.audioBitrate())
          || !fitsViewerBounds(source, box, viewerBounds)) {
        continue;
      }

      fit(request, box, tierBitrate)
          .filter(output -> QualityTier.containing(pictureOf(output)) == tier)
          .map(output -> labelled(output, source))
          .ifPresent(variants::add);
    }

    if (variants.isEmpty()) {
      return fitUntiered(sizing, viewerBounds)
          .map(output -> labelled(output, source).variant())
          .stream()
          .toList();
    }

    return withoutRepeatedLabels(variants);
  }

  private record LabelledVariant(QualityVariant variant, boolean reachesItsTier) {}

  private static LabelledVariant labelled(EncodedVideoOutput output, PictureSize source) {
    var picture = pictureOf(output);
    var tier = QualityTier.containing(picture);
    if (EncodedPictureSizes.reaches(source, picture, tier.box())) {
      return new LabelledVariant(variantBuilder(output).label(tier.label()).build(), true);
    }

    var shorterSide = Math.min(picture.width(), picture.height());
    return new LabelledVariant(variantBuilder(output).label(shorterSide + "p").build(), false);
  }

  // A label names a variant's playlist, so a variant that only its shorter side labels gives way
  // to the variant whose tier has the same label.
  private static List<QualityVariant> withoutRepeatedLabels(List<LabelledVariant> variants) {
    var tierLabels =
        variants.stream()
            .filter(LabelledVariant::reachesItsTier)
            .map(labelled -> labelled.variant().label())
            .collect(Collectors.toSet());
    return variants.stream()
        .filter(
            labelled ->
                labelled.reachesItsTier() || !tierLabels.contains(labelled.variant().label()))
        .map(LabelledVariant::variant)
        .toList();
  }

  private static boolean exceeds(OptionalLong maxBitrate, long bitrate) {
    return maxBitrate.isPresent() && bitrate > maxBitrate.getAsLong();
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

  // Original has no tier, so its variant is untiered.
  private static Optional<EncodedVideoOutput> fitDefaultVariant(Sizing sizing) {
    var limits = sizing.request().viewerLimits();
    var fixedTier = limits.fixedTier();
    if (fixedTier.isEmpty()) {
      return fitUntiered(sizing, limits.maximumPicture());
    }

    return fitFixedQuality(sizing, fixedTier.get());
  }

  // A fixed quality asks for the nominal bitrate of the smallest tier whose box holds the encoded
  // picture, so a source smaller than the quality's box encodes at its own tier's bitrate. The
  // source's known bitrate, the video budget and the entry's codec level cap it.
  private static Optional<EncodedVideoOutput> fitFixedQuality(Sizing sizing, QualityTier tier) {
    var request = sizing.request();
    var limits = request.viewerLimits();
    var source = request.probe().displayedSize();
    var bounds = limits.maximumPicture().boundedBy(tier.box().orientedLike(source));
    var budget = videoBudget(sizing, bounds);
    var bitrate = budget.bitrate();
    var sourceBitrate = sourceBitrate(request);
    if (sourceBitrate.isPresent()) {
      bitrate = Math.min(bitrate, sourceBitrate.getAsLong());
    }

    return fit(request, budget.bounds(), bitrate)
        .map(output -> atNominalBitrate(output, request.codecProfile()));
  }

  // The untiered variant asks for the source's bitrate within its video budget. A probe that
  // names no source bitrate reports 0, so the variant then asks for the nominal bitrate of the
  // smallest tier whose box holds the fitted picture, within the budget. The entry's codec level
  // caps each.
  private static Optional<EncodedVideoOutput> fitUntiered(Sizing sizing, PictureSize bounds) {
    var request = sizing.request();
    var budget = videoBudget(sizing, bounds);
    var sourceBitrate = sourceBitrate(request);
    if (sourceBitrate.isPresent()) {
      return fit(request, budget.bounds(), Math.min(sourceBitrate.getAsLong(), budget.bitrate()));
    }

    return fit(request, budget.bounds(), budget.bitrate())
        .map(output -> atNominalBitrate(output, request.codecProfile()));
  }

  private record VideoBudget(PictureSize bounds, long bitrate) {}

  // The viewer's maximum bitrate covers the video and the audio step. When less than the 360p
  // tier's video bitrate remains, the variant takes the 360p box and the remainder, but never less
  // than the lowest video bitrate of Apple's H.264 ladder, because an encoder reads a zero bitrate
  // as no limit (ADR 0050).
  private static VideoBudget videoBudget(Sizing sizing, PictureSize bounds) {
    var request = sizing.request();
    var maxBitrate = sizing.maxBitrate();
    if (maxBitrate.isEmpty()) {
      return new VideoBudget(bounds, UNCAPPED_BITRATE);
    }

    var remainder = maxBitrate.getAsLong() - sizing.audioBitrate();
    var smallest = QualityTier.TIER_360P;
    if (remainder >= smallest.videoBitrate(request.codecProfile())) {
      return new VideoBudget(bounds, remainder);
    }

    var box = smallest.box().orientedLike(request.probe().displayedSize());
    return new VideoBudget(bounds.boundedBy(box), Math.max(LOWEST_VIDEO_BITRATE, remainder));
  }

  private static OptionalLong sourceBitrate(QualityLadderRequest request) {
    var bitrate = request.probe().bitrate();
    if (bitrate <= 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(bitrate);
  }

  private static EncodedVideoOutput atNominalBitrate(
      EncodedVideoOutput output, EncodeCodecProfile codecProfile) {
    var nominalBitrate = QualityTier.containing(pictureOf(output)).videoBitrate(codecProfile);
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
