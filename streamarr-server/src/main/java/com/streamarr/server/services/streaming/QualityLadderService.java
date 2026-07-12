package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.transcode.engine.model.QualityVariant;
import com.streamarr.transcode.engine.model.RenditionRequest;
import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.TranscodeMode;
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
  private static final long UHD_VIDEO_BITRATE = 15_000_000L;

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
      var targetHeight =
          Math.min(resolveMaxHeight(source, options), STANDARD_TIERS.getLast().height());
      variants.add(
          QualityVariant.builder()
              .width(computeWidthForHeight(source, targetHeight))
              .height(targetHeight)
              .videoBitrate(resolveNonStandardVideoBitrate(source, options, targetHeight))
              .audioBitrate(64_000L)
              .label(targetHeight + "p")
              .build());
    }

    return List.copyOf(variants);
  }

  public RenditionSpec resolveDefaultRendition(StreamSession session) {
    var source = session.getMediaProbe();
    if (!requiresVideoTranscode(session.getTranscodeDecision().transcodeMode())) {
      return new RenditionSpec(
          RenditionRequest.DEFAULT_VARIANT,
          source.width(),
          source.height(),
          Math.max(1, source.bitrate()));
    }

    var options = session.getOptions();
    var targetHeight = Math.min(resolveMaxHeight(source, options), resolveQualityHeight(options));
    return new RenditionSpec(
        RenditionRequest.DEFAULT_VARIANT,
        computeWidthForHeight(source, targetHeight),
        targetHeight,
        resolveFallbackVideoBitrate(options, targetHeight));
  }

  public long resolveDefaultRenditionBandwidth(StreamSession session) {
    var sourceBitrate = session.getMediaProbe().bitrate();
    if (sourceBitrate > 0
        && !requiresVideoTranscode(session.getTranscodeDecision().transcodeMode())) {
      return sourceBitrate;
    }
    return Math.addExact(
        resolveDefaultRendition(session).videoBitrate(),
        session.getTranscodeDecision().audioDecision().bitrate());
  }

  private int resolveMaxHeight(MediaProbe source, StreamingOptions options) {
    var maxHeight = source.height();
    if (options.maxHeight() != null && options.maxHeight() > 0) {
      maxHeight = Math.min(maxHeight, options.maxHeight());
    }
    if (options.maxWidth() == null
        || options.maxWidth() < 2
        || options.maxWidth() >= source.width()) {
      return maxHeight;
    }
    var alignedMaxWidth = options.maxWidth() - options.maxWidth() % 2;
    var widthLimitedHeight =
        Math.max(1, (int) Math.floor((double) alignedMaxWidth * source.height() / source.width()));
    return Math.min(maxHeight, widthLimitedHeight);
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

  private long resolveNonStandardVideoBitrate(
      MediaProbe source, StreamingOptions options, int targetHeight) {
    var maxBitrate = resolveMaxBitrate(options);
    if (source.bitrate() > 0 && targetHeight == source.height()) {
      return maxBitrate == null
          ? source.bitrate()
          : Math.min(source.bitrate(), maxBitrate.longValue());
    }
    return resolveFallbackVideoBitrate(options, targetHeight);
  }

  private long resolveFallbackVideoBitrate(StreamingOptions options, int targetHeight) {
    var maxBitrate = resolveMaxBitrate(options);
    var tierBitrate =
        targetHeight > STANDARD_TIERS.getFirst().height()
            ? UHD_VIDEO_BITRATE
            : STANDARD_TIERS.stream()
                .filter(tier -> tier.height() <= targetHeight)
                .findFirst()
                .map(QualityTier::videoBitrate)
                .orElse(STANDARD_TIERS.getLast().videoBitrate());
    return maxBitrate == null ? tierBitrate : Math.min(tierBitrate, maxBitrate.longValue());
  }

  private static int resolveQualityHeight(StreamingOptions options) {
    var quality = options.quality();
    if (quality == null || quality == VideoQuality.AUTO) {
      return Integer.MAX_VALUE;
    }
    return switch (quality) {
      case LOW_360P -> 360;
      case MEDIUM_480P -> 480;
      case HIGH_720P -> 720;
      case FULL_HD_1080P -> 1080;
      case UHD_4K -> 2160;
      case AUTO -> throw new IllegalStateException("AUTO quality handled before switch");
    };
  }

  private static boolean requiresVideoTranscode(TranscodeMode mode) {
    return mode == TranscodeMode.VIDEO_TRANSCODE || mode == TranscodeMode.FULL_TRANSCODE;
  }
}
