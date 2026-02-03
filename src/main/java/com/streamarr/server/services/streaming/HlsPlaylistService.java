package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HlsPlaylistService {

  private static final Map<String, String> CODEC_STRINGS =
      Map.of(
          "h264", "avc1.640028",
          "av1", "av01.0.08M.08",
          "hevc", "hvc1.1.6.L120.90");

  private final StreamingProperties properties;

  public String generateMasterPlaylist(StreamSession session) {
    var decision = session.getTranscodeDecision();
    var codecString = CODEC_STRINGS.getOrDefault(decision.videoCodecFamily(), "avc1.640028");
    var audioCodecString = "mp4a.40.2";
    var codecs = codecString + "," + audioCodecString;

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");

    if (session.getVariants().isEmpty()) {
      var probe = session.getMediaProbe();
      appendStreamInf(sb, probe.bitrate(), probe.width(), probe.height(), codecs);
      sb.append("stream.m3u8\n");
      return sb.toString();
    }

    for (var variant : session.getVariants()) {
      var bandwidth = variant.videoBitrate() + variant.audioBitrate();
      appendStreamInf(sb, bandwidth, variant.width(), variant.height(), codecs);
      sb.append(variant.label()).append("/stream.m3u8\n");
    }

    return sb.toString();
  }

  private void appendStreamInf(
      StringBuilder sb, long bandwidth, int width, int height, String codecs) {
    sb.append("#EXT-X-STREAM-INF:");
    sb.append("BANDWIDTH=").append(bandwidth);
    sb.append(",RESOLUTION=").append(width).append("x").append(height);
    sb.append(",CODECS=\"").append(codecs).append("\"");
    sb.append("\n");
  }

  public String generateMediaPlaylist(StreamSession session) {
    var decision = session.getTranscodeDecision();
    var container = decision.containerFormat();
    var probe = session.getMediaProbe();
    var segmentDuration = properties.segmentDurationSeconds();
    var totalDurationMs = probe.duration().toMillis();
    var segmentDurationMs = segmentDuration * 1000L;
    var segmentCount = (int) Math.ceil((double) totalDurationMs / segmentDurationMs);
    var extension = container.segmentExtension();

    var sb = new StringBuilder();
    sb.append("#EXTM3U\n");
    sb.append("#EXT-X-VERSION:").append(container.hlsVersion()).append("\n");
    sb.append("#EXT-X-TARGETDURATION:").append(segmentDuration).append("\n");
    sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
    sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n");

    if (container == ContainerFormat.FMP4) {
      sb.append("#EXT-X-MAP:URI=\"init.mp4\"\n");
    }

    for (int i = 0; i < segmentCount; i++) {
      var remainingMs = totalDurationMs - ((long) i * segmentDurationMs);
      var durationMs = Math.min(segmentDurationMs, remainingMs);
      sb.append("#EXTINF:").append(String.format("%.6f", durationMs / 1000.0)).append(",\n");
      sb.append("segment").append(i).append(extension).append("\n");
    }

    sb.append("#EXT-X-ENDLIST\n");

    return sb.toString();
  }
}
