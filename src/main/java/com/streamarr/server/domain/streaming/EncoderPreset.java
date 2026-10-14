package com.streamarr.server.domain.streaming;

import lombok.NonNull;

/**
 * The preset scale that serves every software encoder (ADR 0044), from fastest to slowest. A
 * variant job always carries one of these presets, never Automatic.
 */
public enum EncoderPreset {
  ULTRAFAST,
  SUPERFAST,
  VERYFAST,
  FASTER,
  FAST,
  MEDIUM,
  SLOW,
  SLOWER,
  VERYSLOW;

  /**
   * The preset that Automatic resolves to for a video codec family. A family without a software
   * encoder reaches a job only as a stream copy, which ignores the preset.
   */
  public static EncoderPreset automaticFor(@NonNull String videoCodecFamily) {
    return switch (videoCodecFamily) {
      case "h264", "av1" -> FASTER;
      case "hevc" -> VERYFAST;
      default -> FASTER;
    };
  }
}
