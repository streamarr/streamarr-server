package com.streamarr.server.domain.streaming;

import java.util.Set;
import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {

  // Every HLS variant is multiplexed fragmented MP4, which carries these audio codecs.
  private static final Set<String> DELIVERABLE_CODECS =
      Set.of("aac", "ac3", "eac3", "mp3", "flac", "opus", "alac");

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
    return DELIVERABLE_CODECS;
  }

  public String hlsCodecString() {
    if (codec == null) {
      return "";
    }
    return switch (codec) {
      case "ac3" -> "ac-3";
      case "eac3" -> "ec-3";
      default -> "mp4a.40.2";
    };
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
