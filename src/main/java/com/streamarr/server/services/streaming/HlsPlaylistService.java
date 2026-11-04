package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.EncodedVideoOutput;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoEncodeDecision;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamCopy;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalDouble;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HlsPlaylistService {

  private static final String AUDIO_GROUP_ID = "audio";
  private static final int FRAME_RATE_DIGITS = 3;

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

    // Only a copy runs without a ladder. It keeps the source's video bitrate, the displayed source
    // dimensions and, when it is known, the source's frame rate.
    if (session.getVariants().isEmpty()) {
      var probe = session.getMediaProbe();
      var size = probe.displayedSize();
      var codecs = codecs(session, copiedVideoCodecString(session));
      appendStreamInf(
          sb, copyBandwidth(probe, audio), size.width(), size.height(), codecs, hasAudio);
      appendCopiedFrameRate(sb, probe);
      appendVideoRange(sb, session);
      appendSupplementalCodecs(sb, session);
      sb.append("\n");
      sb.append("stream.m3u8?t=").append(token).append("\n");
      return sb.toString();
    }

    for (var variant : session.getVariants()) {
      var bandwidth = Bandwidth.withHeadroom(variant.averageBandwidth(audio.bitrate()));
      var codecs = codecs(session, encodedVideoCodecString(session, variant));
      appendStreamInf(sb, bandwidth, variant.width(), variant.height(), codecs, hasAudio);
      appendFrameRate(sb, variant);
      appendVideoRange(sb, session);
      sb.append("\n");
      sb.append(variant.label()).append("/stream.m3u8?t=").append(token).append("\n");
    }

    return sb.toString();
  }

  // A measured copy advertises its measured peak. An unmeasured copy, like an encoded variant, adds
  // headroom to its average (ADR 0054).
  private Bandwidth copyBandwidth(MediaProbe probe, AudioDecision audio) {
    var playlist = CopyMediaPlaylist.of(probe, properties);
    var average = VideoCopyBandwidth.averageBandwidth(probe, audio, playlist);
    var peak = VideoCopyBandwidth.peakBandwidth(probe, audio, playlist);
    if (peak.isEmpty()) {
      return Bandwidth.withHeadroom(average);
    }

    return new Bandwidth(peak.getAsLong(), average);
  }

  private static String codecs(StreamSession session, String videoCodecString) {
    var audioCodecString = session.getTranscodeDecision().audioDecision().hlsCodecString();
    if (audioCodecString.isEmpty()) {
      return videoCodecString;
    }

    return videoCodecString + "," + audioCodecString;
  }

  // An encoded variant advertises the codec string that its job attempts force.
  private static String encodedVideoCodecString(StreamSession session, QualityVariant variant) {
    var decision = session.getTranscodeDecision();
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

  // A copy advertises the codec string that the probe stored, which names the sample entry that
  // the copy carries.
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

  /** A variant's BANDWIDTH and AVERAGE-BANDWIDTH (RFC 8216 §4.3.4.2). */
  private record Bandwidth(long peak, long average) {

    // RFC 8216 §4.3.4.2 defines BANDWIDTH as the peak segment bit rate and AVERAGE-BANDWIDTH as
    // the average. An encoded variant and an unmeasured copy know only an average, so their
    // BANDWIDTH adds headroom for container overhead and rate variation (ADR 0044). Encoded
    // variants are capped with -maxrate. Probed source rates remain estimates.
    private static final int PEAK_HEADROOM_PERCENT = 20;

    static Bandwidth withHeadroom(long average) {
      return new Bandwidth(average + average * PEAK_HEADROOM_PERCENT / 100, average);
    }
  }

  private void appendStreamInf(
      StringBuilder sb,
      Bandwidth bandwidth,
      int width,
      int height,
      String codecs,
      boolean hasAudio) {
    sb.append("#EXT-X-STREAM-INF:");
    sb.append("BANDWIDTH=").append(bandwidth.peak());
    sb.append(",AVERAGE-BANDWIDTH=").append(bandwidth.average());
    sb.append(",RESOLUTION=").append(width).append("x").append(height);
    sb.append(",CODECS=\"").append(codecs).append("\"");
    if (hasAudio) {
      sb.append(",AUDIO=\"").append(AUDIO_GROUP_ID).append("\"");
    }
  }

  // RFC 8216 section 4.3.4.2 gives FRAME-RATE as the maximum rate, to three decimals. An encoded
  // variant runs at its own frame rate.
  private static void appendFrameRate(StringBuilder sb, QualityVariant variant) {
    var frameRate = variant.frameRate();
    appendFrameRate(
        sb,
        BigDecimal.valueOf(frameRate.numerator())
            .divide(
                BigDecimal.valueOf(frameRate.denominator()),
                FRAME_RATE_DIGITS,
                RoundingMode.HALF_UP));
  }

  // A copy writes the frame rate that the copy match checks against the entry for a probe version
  // 5 outcome. The probe measures no maximum until #652, so a variable-rate source can briefly run
  // faster. A copy whose frame rate the rule refused writes none, even when it announces PQ or HLG:
  // no encode can replace it (#446), and players other than AVPlayer may play it (ADR 0013).
  private static void appendCopiedFrameRate(StringBuilder sb, MediaProbe probe) {
    var frameRate =
        VideoStreamCopy.of(probe).map(VideoStreamCopy::frameRate).orElseGet(OptionalDouble::empty);
    if (frameRate.isEmpty()) {
      return;
    }

    appendFrameRate(
        sb,
        BigDecimal.valueOf(frameRate.getAsDouble())
            .setScale(FRAME_RATE_DIGITS, RoundingMode.HALF_UP));
  }

  private static void appendFrameRate(StringBuilder sb, BigDecimal frameRate) {
    sb.append(",FRAME-RATE=").append(frameRate.toPlainString());
  }

  // The playlist announces the planned output's range (ADR 0013). A copy that keeps Dolby Vision
  // under an SDR compatible base declares its SDR base layer explicitly and announces no Dolby
  // Vision (ADR 0036). Every other SDR output announces none, which HLS reads as SDR.
  private static void appendVideoRange(StringBuilder sb, StreamSession session) {
    var decision = session.getTranscodeDecision();
    var range = decision.videoRange();
    if (range != VideoRange.SDR) {
      sb.append(",VIDEO-RANGE=").append(range.name());
      return;
    }

    var keepsDolbyVision =
        decision.plannedVideoOutput() instanceof PlannedVideoOutput.StreamCopy copy
            && copy.dynamicRange().dolbyVision() instanceof DolbyVision.Present;
    if (keepsDolbyVision) {
      sb.append(",VIDEO-RANGE=SDR");
    }
  }

  // A copy that keeps Dolby Vision on a PQ or HLG base announces the Dolby Vision beside its base
  // sample entry's CODECS, at the decision's level (ADR 0013).
  private static void appendSupplementalCodecs(StringBuilder sb, StreamSession session) {
    session
        .getTranscodeDecision()
        .videoDolbyVisionOutput()
        .flatMap(DolbyVisionOutput::supplementalCodecs)
        .ifPresent(codecs -> sb.append(",SUPPLEMENTAL-CODECS=\"").append(codecs).append("\""));
  }

  /**
   * The playlist always covers the full media duration on an absolute timeline that starts at zero,
   * so player position and duration match real media time. An encoded segment {@code i} is media
   * time {@code [i * targetSegmentDuration, (i + 1) * targetSegmentDuration)}; a stream copy's
   * segment runs from its opening keyframe to the next one.
   */
  public String generateMediaPlaylist(StreamSession session, String variantLabel, String token) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var extinfDurations = ExtinfDurations.of(timeline);

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");
    sb.append("#EXT-X-VERSION:").append(PLAYLIST_VERSION).append("\n");
    if (isKeyframeVerified(session, variantLabel)
        && !isEveryVideoVariantKeyframeVerified(session)) {
      sb.append(INDEPENDENT_SEGMENTS_TAG);
    }

    sb.append("#EXT-X-TARGETDURATION:")
        .append(ExtinfDurations.targetDurationSeconds(timeline, extinfDurations))
        .append("\n");
    sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
    sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
    sb.append("#EXT-X-MAP:URI=\"")
        .append(SegmentNames.INITIALIZATION_SEGMENT_NAME)
        .append("?t=")
        .append(token)
        .append("\"\n");

    for (int i = 0; i < extinfDurations.size(); i++) {
      sb.append("#EXTINF:").append(extinfDurations.get(i).toPlainString()).append(",\n");
      sb.append(SegmentNames.mediaSegmentName(i)).append("?t=").append(token).append("\n");
    }

    sb.append("#EXT-X-ENDLIST\n");

    return sb.toString();
  }
}
