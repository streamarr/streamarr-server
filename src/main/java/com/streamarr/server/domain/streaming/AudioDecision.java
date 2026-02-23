package com.streamarr.server.domain.streaming;

import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {

  public static AudioDecision stereoAac() {
    return new AudioDecision(AudioMode.TRANSCODE, "aac", 2, 128_000L);
  }

  public static AudioDecision copy(String codec, int channels, long bitrate) {
    return new AudioDecision(AudioMode.COPY, codec, channels, bitrate);
  }

  public String hlsCodecString() {
    return switch (codec) {
      case "ac3" -> "ac-3";
      case "eac3" -> "ec-3";
      default -> "mp4a.40.2";
    };
  }

  public static int normalizeChannels(int sourceChannels) {
    if (sourceChannels <= 0 || sourceChannels == 2) {
      return 2;
    }
    if (sourceChannels == 1) {
      return 1;
    }
    if (sourceChannels >= 5 && sourceChannels <= 6) {
      return 6;
    }
    if (sourceChannels >= 7) {
      return 8;
    }
    return 2;
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
