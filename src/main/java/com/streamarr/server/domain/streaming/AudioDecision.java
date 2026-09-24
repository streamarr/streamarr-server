package com.streamarr.server.domain.streaming;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {

  // Every HLS variant is multiplexed fragmented MP4, which carries exactly these audio codecs. Each
  // maps to the RFC 6381 codecs parameter that the Apple HLS authoring specification names for it.
  private static final Map<String, String> HLS_CODEC_STRINGS =
      Map.of(
          "aac", "mp4a.40.2",
          "ac3", "ac-3",
          "eac3", "ec-3",
          "mp3", "mp4a.40.34",
          "flac", "fLaC",
          "opus", "Opus",
          "alac", "alac");

  public static AudioDecision stereoAac() {
    return new AudioDecision(AudioMode.TRANSCODE, "aac", 2, 128_000L);
  }

  public static AudioDecision copy(String codec, int channels, long bitrate) {
    return new AudioDecision(AudioMode.COPY, codec, channels, bitrate);
  }

  public static AudioDecision none() {
    return new AudioDecision(AudioMode.NONE, null, 0, 0L);
  }

  /** The audio codecs that HLS delivery carries, by their probed codec names. */
  public static Set<String> deliverableCodecs() {
    return HLS_CODEC_STRINGS.keySet();
  }

  public String hlsCodecString() {
    if (codec == null) {
      return "";
    }

    return Optional.ofNullable(HLS_CODEC_STRINGS.get(codec))
        .orElseThrow(
            () -> new IllegalStateException("HLS delivery cannot carry audio codec " + codec));
  }

  public static int normalizeChannels(int sourceChannels) {
    return switch (sourceChannels) {
      case 1 -> 1;
      case 5, 6 -> 6;
      default -> sourceChannels >= 7 ? 8 : 2;
    };
  }

  public static long bitrateForChannels(int channels) {
    return switch (channels) {
      case 1 -> 64_000L;
      case 6 -> 384_000L;
      case 8 -> 512_000L;
      default -> 128_000L;
    };
  }
}
