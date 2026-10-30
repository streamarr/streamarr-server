package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecimalSeconds;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.EncodedVideoOutput;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoEncodeDecision;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HlsPlaylistService {

  private static final String AUDIO_GROUP_ID = "audio";
  private static final int EXTINF_DIGITS = 6;

  private static final String INDEPENDENT_SEGMENTS_TAG = "#EXT-X-INDEPENDENT-SEGMENTS\n";

  // RFC 8216 section 7: EXT-X-MAP in a playlist that is not I-frame-only needs version 6.
  private static final int PLAYLIST_VERSION = 6;

  private final StreamingProperties properties;

  public String generateMultivariantPlaylist(StreamSession session, String token) {
    var audio = session.getTranscodeDecision().audioDecision();
    var hasAudio = audio.mode() != AudioMode.NONE;

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");
    if (isEveryVideoVariantKeyframeVerified(session)) {
      sb.append(INDEPENDENT_SEGMENTS_TAG);
    }

    if (hasAudio) {
      appendAudioRendition(sb, audio.channels());
    }

    // Only a copy runs without a ladder. It keeps the source bitrate and the displayed source
    // dimensions, and leaves FRAME-RATE out.
    if (session.getVariants().isEmpty()) {
      var probe = session.getMediaProbe();
      var size = probe.displayedSize();
      var codecs = codecs(session, copiedVideoCodecString(session));
      appendStreamInf(sb, probe.bitrate(), size.width(), size.height(), codecs, hasAudio);
      appendVideoRange(sb, session);
      sb.append("\n");
      sb.append("stream.m3u8?t=").append(token).append("\n");
      return sb.toString();
    }

    for (var variant : session.getVariants()) {
      var averageBandwidth = variant.averageBandwidth(audio.bitrate());
      var codecs = codecs(session, videoCodecString(session, variant));
      appendStreamInf(sb, averageBandwidth, variant.width(), variant.height(), codecs, hasAudio);
      appendFrameRate(sb, session, variant);
      appendVideoRange(sb, session);
      sb.append("\n");
      sb.append(variant.label()).append("/stream.m3u8?t=").append(token).append("\n");
    }

    return sb.toString();
  }

  private static String codecs(StreamSession session, String videoCodecString) {
    var audioCodecString = session.getTranscodeDecision().audioDecision().hlsCodecString();
    if (audioCodecString.isEmpty()) {
      return videoCodecString;
    }

    return videoCodecString + "," + audioCodecString;
  }

  // An encoded variant advertises the codec string that its job attempts force. A copy advertises
  // the codec string that the probe stored, which names the sample entry that the copy carries.
  private static String videoCodecString(StreamSession session, QualityVariant variant) {
    var decision = session.getTranscodeDecision();
    if (decision.transcodeMode().copiesVideo()) {
      return copiedVideoCodecString(session);
    }

    return VideoEncodeDecision.of(
            EncodedVideoOutput.builder()
                .codecFamily(decision.videoCodecFamily())
                .width(variant.width())
                .height(variant.height())
                .frameRate(variant.frameRate())
                .bitrate(variant.videoBitrate())
                .build())
        .codecString();
  }

  private static String copiedVideoCodecString(StreamSession session) {
    return session
        .getTranscodeDecision()
        .videoSource()
        .flatMap(VideoPlaybackFacts::codecString)
        .orElseThrow();
  }

  // ADR 0037: the tag is true only for a variant pinned to a keyframe-verified encoder backend. A
  // stream copy and a variant without a pinned backend never qualify.
  private static boolean isKeyframeVerified(StreamSession session, String variantLabel) {
    return session
        .getPinnedEncoder(variantLabel)
        .map(WorkerVerifiedEncoder::keyframeVerified)
        .orElse(false);
  }

  private static boolean isEveryVideoVariantKeyframeVerified(StreamSession session) {
    if (session.getVariants().isEmpty()) {
      return isKeyframeVerified(session, StreamSession.defaultVariant());
    }

    return session.getVariants().stream()
        .allMatch(variant -> isKeyframeVerified(session, variant.label()));
  }

  private void appendAudioRendition(StringBuilder sb, int audioChannels) {
    sb.append("#EXT-X-MEDIA:");
    sb.append("TYPE=AUDIO");
    sb.append(",GROUP-ID=\"").append(AUDIO_GROUP_ID).append("\"");
    sb.append(",NAME=\"Audio\"");
    sb.append(",DEFAULT=YES");
    sb.append(",AUTOSELECT=YES");
    sb.append(",CHANNELS=\"").append(audioChannels).append("\"");
    sb.append("\n");
  }

  // RFC 8216 §4.3.4.2 defines BANDWIDTH as the peak segment bit rate and AVERAGE-BANDWIDTH
  // as the average. The rates available here are averages, so BANDWIDTH adds headroom for
  // container overhead and rate variation. Encoded variants are capped with -maxrate.
  // Probed source rates remain estimates.
  private static final int PEAK_HEADROOM_PERCENT = 20;

  private void appendStreamInf(
      StringBuilder sb,
      long averageBandwidth,
      int width,
      int height,
      String codecs,
      boolean hasAudio) {
    var peakBandwidth = averageBandwidth + averageBandwidth * PEAK_HEADROOM_PERCENT / 100;
    sb.append("#EXT-X-STREAM-INF:");
    sb.append("BANDWIDTH=").append(peakBandwidth);
    sb.append(",AVERAGE-BANDWIDTH=").append(averageBandwidth);
    sb.append(",RESOLUTION=").append(width).append("x").append(height);
    sb.append(",CODECS=\"").append(codecs).append("\"");
    if (hasAudio) {
      sb.append(",AUDIO=\"").append(AUDIO_GROUP_ID).append("\"");
    }
  }

  // RFC 8216 section 4.3.4.2 gives FRAME-RATE as the maximum rate, to three decimals. An encoded
  // variant runs at its own frame rate. A copy keeps the source's timing, whose maximum rate the
  // probe does not measure, so its entry leaves FRAME-RATE out.
  private static void appendFrameRate(
      StringBuilder sb, StreamSession session, QualityVariant variant) {
    if (session.getTranscodeDecision().transcodeMode().copiesVideo()) {
      return;
    }

    var frameRate = variant.frameRate();
    sb.append(",FRAME-RATE=")
        .append(
            BigDecimal.valueOf(frameRate.numerator())
                .divide(BigDecimal.valueOf(frameRate.denominator()), 3, RoundingMode.HALF_UP)
                .toPlainString());
  }

  // A copy of a Dolby Vision source with an SDR compatible base declares its SDR base layer
  // explicitly and announces no Dolby Vision (ADR 0013, ADR 0036).
  private static void appendVideoRange(StringBuilder sb, StreamSession session) {
    var decision = session.getTranscodeDecision();
    var copiesDolbyVisionWithSdrBase =
        decision.transcodeMode().copiesVideo()
            && decision
                .videoSource()
                .map(VideoPlaybackFacts::dynamicRange)
                .filter(range -> range.compatibleBase() == CompatibleBase.SDR)
                .filter(range -> range.dolbyVision() instanceof DolbyVision.Present)
                .isPresent();
    if (copiesDolbyVisionWithSdrBase) {
      sb.append(",VIDEO-RANGE=SDR");
    }
  }

  /**
   * The playlist always covers the full media duration on an absolute timeline that starts at zero,
   * so player position and duration match real media time. An encoded segment {@code i} is media
   * time {@code [i * targetSegmentDuration, (i + 1) * targetSegmentDuration)}; a stream copy's
   * segment runs from its opening keyframe to the next one.
   */
  public String generateMediaPlaylist(StreamSession session, String variantLabel, String token) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var segmentCount = timeline.mediaSegmentCount();
    var extinfDurations =
        IntStream.range(0, segmentCount)
            .mapToObj(index -> extinfSeconds(timeline.mediaSegmentDuration(index)))
            .toList();

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");
    sb.append("#EXT-X-VERSION:").append(PLAYLIST_VERSION).append("\n");
    if (isKeyframeVerified(session, variantLabel)
        && !isEveryVideoVariantKeyframeVerified(session)) {
      sb.append(INDEPENDENT_SEGMENTS_TAG);
    }

    sb.append("#EXT-X-TARGETDURATION:")
        .append(targetDurationSeconds(timeline, extinfDurations))
        .append("\n");
    sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
    sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
    sb.append("#EXT-X-MAP:URI=\"")
        .append(SegmentNames.INITIALIZATION_SEGMENT_NAME)
        .append("?t=")
        .append(token)
        .append("\"\n");

    for (int i = 0; i < segmentCount; i++) {
      sb.append("#EXTINF:").append(extinfDurations.get(i).toPlainString()).append(",\n");
      sb.append(SegmentNames.mediaSegmentName(i)).append("?t=").append(token).append("\n");
    }

    sb.append("#EXT-X-ENDLIST\n");

    return sb.toString();
  }

  private static BigDecimal extinfSeconds(Duration duration) {
    return DecimalSeconds.of(duration).setScale(EXTINF_DIGITS, RoundingMode.HALF_UP);
  }

  // RFC 8216 §4.3.3.1: every EXTINF, rounded to the nearest integer, must not exceed the target
  // duration. A stream copy's segment can run longer than the target segment duration.
  private static int targetDurationSeconds(
      MediaSegmentTimeline timeline, List<BigDecimal> extinfDurations) {
    var targetDuration = timeline.targetSegmentDurationSeconds();
    for (var extinf : extinfDurations) {
      targetDuration =
          Math.max(targetDuration, extinf.setScale(0, RoundingMode.HALF_UP).intValueExact());
    }

    return targetDuration;
  }
}
