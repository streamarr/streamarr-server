package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.StreamSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HlsPlaylistService {

  private static final String AUDIO_GROUP_ID = "audio";
  private static final int NANOSECOND_DIGITS = 9;
  private static final int EXTINF_DIGITS = 6;

  // RFC 8216 section 7: EXT-X-MAP in a playlist that is not I-frame-only needs version 6.
  private static final int PLAYLIST_VERSION = 6;

  private static final Map<String, String> CODEC_STRINGS =
      Map.of(
          "h264", "avc1.640028",
          "av1", "av01.0.08M.08",
          "hevc", "hvc1.1.6.L120.90");

  private final StreamingProperties properties;

  public String generateMultivariantPlaylist(StreamSession session, String token) {
    var decision = session.getTranscodeDecision();
    var audio = decision.audioDecision();
    var videoCodecString = CODEC_STRINGS.getOrDefault(decision.videoCodecFamily(), "avc1.640028");
    var audioCodecString = audio.hlsCodecString();
    var codecs =
        audioCodecString.isEmpty() ? videoCodecString : videoCodecString + "," + audioCodecString;
    var hasAudio = audio.mode() != AudioMode.NONE;

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");

    if (hasAudio) {
      appendAudioRendition(sb, audio.channels());
    }

    if (session.getVariants().isEmpty()) {
      var probe = session.getMediaProbe();
      appendStreamInf(sb, probe.bitrate(), probe.width(), probe.height(), codecs, hasAudio);
      sb.append("stream.m3u8?t=").append(token).append("\n");
      return sb.toString();
    }

    for (var variant : session.getVariants()) {
      var averageBandwidth = variant.videoBitrate() + audio.bitrate();
      appendStreamInf(sb, averageBandwidth, variant.width(), variant.height(), codecs, hasAudio);
      sb.append(variant.label()).append("/stream.m3u8?t=").append(token).append("\n");
    }

    return sb.toString();
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
    sb.append("\n");
  }

  /**
   * The playlist always covers the full media duration on an absolute timeline that starts at zero,
   * so player position and duration match real media time. An encoded segment {@code i} is media
   * time {@code [i * targetSegmentDuration, (i + 1) * targetSegmentDuration)}; a stream copy's
   * segment runs from its opening keyframe to the next one.
   */
  public String generateMediaPlaylist(StreamSession session, String token) {
    var timeline = MediaSegmentTimelines.of(session, properties);
    var segmentCount = timeline.mediaSegmentCount();
    var extinfDurations =
        IntStream.range(0, segmentCount)
            .mapToObj(index -> extinfSeconds(timeline.mediaSegmentDuration(index)))
            .toList();

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");
    sb.append("#EXT-X-VERSION:").append(PLAYLIST_VERSION).append("\n");
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
    return BigDecimal.valueOf(duration.toNanos(), NANOSECOND_DIGITS)
        .setScale(EXTINF_DIGITS, RoundingMode.HALF_UP);
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
