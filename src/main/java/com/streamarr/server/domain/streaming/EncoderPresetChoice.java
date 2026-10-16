package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Optional;
import lombok.NonNull;

/**
 * The server's preset setting (ADR 0044): Automatic, or one fixed preset from the scale that every
 * software encoder uses.
 */
public enum EncoderPresetChoice {
  AUTOMATIC,
  SUPERFAST,
  VERYFAST,
  FASTER,
  FAST,
  MEDIUM,
  SLOW,
  SLOWER,
  VERYSLOW;

  /** The preset that a variant of this video codec family runs under this choice. */
  public EncoderPreset resolveFor(@NonNull String videoCodecFamily) {
    if (this == AUTOMATIC) {
      return EncoderPreset.automaticFor(videoCodecFamily);
    }

    return EncoderPreset.valueOf(name());
  }

  /** The choice whose name matches exactly, or empty for a name outside the setting. */
  public static Optional<EncoderPresetChoice> named(@NonNull String name) {
    return Arrays.stream(values()).filter(choice -> choice.name().equals(name)).findFirst();
  }
}
