package com.streamarr.transcode.engine.model;

import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {

  private static final long MAX_SAFE_BITRATE = Long.MAX_VALUE / 2;

  public AudioDecision {
    if (mode == null) {
      throw new IllegalArgumentException("Audio mode is required");
    }
    if (mode == AudioMode.NONE && (codec != null || channels != 0 || bitrate != 0)) {
      throw new IllegalArgumentException("Excluded audio cannot carry encoding values");
    }
    if (mode != AudioMode.NONE
        && (codec == null
            || codec.isBlank()
            || channels < 1
            || bitrate < 0
            || bitrate > MAX_SAFE_BITRATE)) {
      throw new IllegalArgumentException("Included audio values are invalid");
    }
    if (mode == AudioMode.TRANSCODE && bitrate == 0) {
      throw new IllegalArgumentException("Transcoded audio bitrate must be positive");
    }
  }

  public static AudioDecision stereoAac() {
    return new AudioDecision(AudioMode.TRANSCODE, "aac", 2, 128_000L);
  }

  public static AudioDecision copy(String codec, int channels, long bitrate) {
    return new AudioDecision(AudioMode.COPY, codec, channels, bitrate);
  }

  public static AudioDecision none() {
    return new AudioDecision(AudioMode.NONE, null, 0, 0L);
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
