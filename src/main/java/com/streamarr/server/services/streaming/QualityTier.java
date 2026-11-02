package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.EncodedVariantRequest;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * A named picture box that the quality ladder offers. The box bounds a picture in either
 * orientation, and Auto labels a variant that reaches the box with the tier's name (ADR 0050).
 */
enum QualityTier {
  // The AV1 2160p and 1440p bitrates are the lowest 3840x2160 and the 2560x1440 rates of the HEVC
  // ladder in Apple's HLS Authoring Specification for Apple Devices, item 1.25. Apple's H.264
  // ladder stops at 1080p.
  TIER_2160P("2160p", new PictureSize(3840, 2160), 11_600_000L, 20_000_000L),
  TIER_1440P("1440p", new PictureSize(2560, 1440), 8_100_000L, 12_000_000L),
  TIER_1080P("1080p", new PictureSize(1920, 1080), 5_000_000L, 5_000_000L),
  TIER_720P("720p", new PictureSize(1280, 720), 3_000_000L, 3_000_000L),
  TIER_480P("480p", new PictureSize(854, 480), 1_500_000L, 1_500_000L),
  TIER_360P("360p", new PictureSize(640, 360), 800_000L, 800_000L);

  private static final List<QualityTier> LARGEST_FIRST = Arrays.asList(values());

  private final String label;
  private final PictureSize box;
  private final long av1VideoBitrate;
  private final long h264VideoBitrate;

  QualityTier(String label, PictureSize box, long av1VideoBitrate, long h264VideoBitrate) {
    this.label = label;
    this.box = box;
    this.av1VideoBitrate = av1VideoBitrate;
    this.h264VideoBitrate = h264VideoBitrate;
  }

  String label() {
    return label;
  }

  PictureSize box() {
    return box;
  }

  /** The tier's nominal video bitrate for the codec family that the encode forces. */
  long videoBitrate(@NonNull EncodeCodecProfile codecProfile) {
    return switch (codecProfile) {
      case AV1_MAIN -> av1VideoBitrate;
      case H264_HIGH -> h264VideoBitrate;
    };
  }

  /** The tier whose box a fixed quality has, or empty for Auto and Original. */
  static Optional<QualityTier> ofFixedQuality(@NonNull VideoQuality quality) {
    return switch (quality) {
      case LOW_360P -> Optional.of(TIER_360P);
      case MEDIUM_480P -> Optional.of(TIER_480P);
      case HIGH_720P -> Optional.of(TIER_720P);
      case FULL_HD_1080P -> Optional.of(TIER_1080P);
      case UHD_4K -> Optional.of(TIER_2160P);
      case AUTO, ORIGINAL -> Optional.empty();
    };
  }

  /**
   * The playback choices that a source offers a client capability declaration, in the order that a
   * quality control lists them: Original, Auto, then the fixed quality of each tier that the ladder
   * offers for the source, largest first. A fixed quality larger than the source would not enlarge
   * it, and one whose box no capability entry reaches would play a smaller tier's picture, so both
   * are left out (ADR 0050).
   */
  static List<VideoQuality> offeredChoicesFor(
      @NonNull MediaProbe source, @NonNull ClientCapabilityDeclaration declaration) {
    var choices = new ArrayList<VideoQuality>();
    choices.add(VideoQuality.ORIGINAL);
    choices.add(VideoQuality.AUTO);
    offeredFor(source.displayedSize()).stream()
        .filter(tier -> tier.isReachedIn(source, declaration))
        .map(QualityTier::fixedQuality)
        .flatMap(Optional::stream)
        .forEach(choices::add);
    return List.copyOf(choices);
  }

  // A copy plays the source's own picture, so it reaches the tier when the source fits the tier's
  // box. An encode reaches the tier when one capability entry fits the tier's picture, in a codec
  // family that the server encodes, without shrinking it into a smaller tier's box. The workers
  // connected now do not count, because the offered choices last for the whole playback session.
  private boolean isReachedIn(MediaProbe source, ClientCapabilityDeclaration declaration) {
    var picture = source.displayedSize();
    if (picture.fitsWithinEitherOrientation(box)
        && VideoStreamCopy.of(source).filter(declaration::acceptsHlsCopy).isPresent()) {
      return true;
    }

    var frameRate = source.videoPlaybackFacts().flatMap(VideoPlaybackFacts::encodedFrameRate);
    if (frameRate.isEmpty()) {
      return false;
    }

    return Arrays.stream(EncodeCodecProfile.values())
        .map(
            codecProfile ->
                EncodedVariantRequest.builder()
                    .codecProfile(codecProfile)
                    .source(picture)
                    .bounds(box.orientedLike(picture))
                    .frameRate(frameRate.get())
                    .bitrate(videoBitrate(codecProfile))
                    .build())
        .map(declaration::fitHlsEncode)
        .flatMap(Optional::stream)
        .anyMatch(output -> containing(new PictureSize(output.width(), output.height())) == this);
  }

  // The fixed quality whose box this tier has. No fixed quality has the 1440p box.
  private Optional<VideoQuality> fixedQuality() {
    return switch (this) {
      case TIER_2160P -> Optional.of(VideoQuality.UHD_4K);
      case TIER_1440P -> Optional.empty();
      case TIER_1080P -> Optional.of(VideoQuality.FULL_HD_1080P);
      case TIER_720P -> Optional.of(VideoQuality.HIGH_720P);
      case TIER_480P -> Optional.of(VideoQuality.MEDIUM_480P);
      case TIER_360P -> Optional.of(VideoQuality.LOW_360P);
    };
  }

  // The tier whose box holds the source and every smaller tier. The smallest tier has no smaller
  // box, so every source is offered it.
  static List<QualityTier> offeredFor(@NonNull PictureSize source) {
    var sourceTier = LARGEST_FIRST.indexOf(containing(source));
    return LARGEST_FIRST.subList(sourceTier, LARGEST_FIRST.size());
  }

  // The smallest tier whose box holds the picture, or the largest tier when none does.
  static QualityTier containing(@NonNull PictureSize picture) {
    return LARGEST_FIRST.reversed().stream()
        .filter(tier -> picture.fitsWithinEitherOrientation(tier.box()))
        .findFirst()
        .orElse(LARGEST_FIRST.getFirst());
  }
}
