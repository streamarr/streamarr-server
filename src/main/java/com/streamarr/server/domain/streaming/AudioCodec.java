package com.streamarr.server.domain.streaming;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** An audio codec that an audio capability entry names (ADR 0049). */
public enum AudioCodec {
  AAC("aac"),
  AC3("ac3"),
  EAC3("eac3"),
  OPUS("opus"),
  FLAC("flac"),
  ALAC("alac");

  // The plain aac declaration covers LC, HE-AAC and HE-AAC v2.
  private static final Set<String> DECLARED_AAC_CODEC_STRINGS =
      Set.of("mp4a.40.2", "mp4a.40.5", "mp4a.40.29");

  private final String probedName;

  AudioCodec(String probedName) {
    this.probedName = probedName;
  }

  /** The codec name that the probe stores for a stream of this codec, such as {@code eac3}. */
  public String probedName() {
    return probedName;
  }

  /** The codec whose probed name this is, or empty for a codec that no entry can name. */
  public static Optional<AudioCodec> ofProbedName(String probedName) {
    return Arrays.stream(values()).filter(codec -> codec.probedName.equals(probedName)).findFirst();
  }

  /**
   * Whether a declaration of this codec covers a stream with this codec string. An AAC declaration
   * covers only the AAC profiles that every AAC decoder plays.
   */
  public boolean coversCodecString(String codecString) {
    return this != AAC || DECLARED_AAC_CODEC_STRINGS.contains(codecString);
  }
}
