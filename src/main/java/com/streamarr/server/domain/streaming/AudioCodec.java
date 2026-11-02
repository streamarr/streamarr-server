package com.streamarr.server.domain.streaming;

/** An audio codec that an audio capability entry names (ADR 0049). */
public enum AudioCodec {
  AAC("aac"),
  AC3("ac3"),
  EAC3("eac3"),
  OPUS("opus"),
  FLAC("flac"),
  ALAC("alac");

  private final String probedName;

  AudioCodec(String probedName) {
    this.probedName = probedName;
  }

  /** The codec name that the probe stores for a stream of this codec, such as {@code eac3}. */
  public String probedName() {
    return probedName;
  }
}
